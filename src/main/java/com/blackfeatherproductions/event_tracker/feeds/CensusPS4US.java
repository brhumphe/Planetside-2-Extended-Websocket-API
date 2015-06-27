package com.blackfeatherproductions.event_tracker.feeds;

import java.util.Date;
import java.util.Map.Entry;

import com.blackfeatherproductions.event_tracker.Config;
import com.blackfeatherproductions.event_tracker.EventTracker;
import com.blackfeatherproductions.event_tracker.QueryManager;
import com.blackfeatherproductions.event_tracker.Utils;
import com.blackfeatherproductions.event_tracker.data_dynamic.WorldInfo;
import com.blackfeatherproductions.event_tracker.data_static.World;
import com.blackfeatherproductions.event_tracker.Environment;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class CensusPS4US
{
    private final QueryManager queryManager = EventTracker.getQueryManager();
    private final Config config = EventTracker.getConfig();

    //Client/Websocket
    HttpClientOptions options = new HttpClientOptions()
            .setDefaultHost("push.planetside2.com")
            .setDefaultPort(443)
            .setSsl(true)
            .setMaxWebsocketFrameSize(1000000)
            .setReceiveBufferSize(1000000)
            .setSendBufferSize(1000000)
            .setKeepAlive(true);
    private final HttpClient client;
    private WebSocket websocket;
    
    //Connection Stuff
    private WebsocketConnectState websocketConnectState = WebsocketConnectState.CLOSED;
    private long lastHeartbeat = 0;
    private long startTime = 0;
    
    private List<World> managedWorlds = new ArrayList<>(); //Worlds managed by this feed.

    //================================================================================
    // Constructor
    //================================================================================
    
    public CensusPS4US()
    {
        Vertx vertx = EventTracker.getVertx();

        client = vertx.createHttpClient(options);

        //Reconnects the websocket if it is not online, or is not responding.
        vertx.setPeriodic(10000, id ->
        {
            //Connect the websocket if it is closed.
            if (websocketConnectState.equals(WebsocketConnectState.CLOSED))
            {
                EventTracker.getLogger().info("[PS2 PS4-US] Reconnecting...");
                connectWebsocket();
            }

            //If we have not received a heartbeat in the last 2 minutes, disconnect, then restart the connection
            else if (!websocketConnectState.equals(WebsocketConnectState.CONNECTING) && lastHeartbeat != 0 && (new Date().getTime()) - lastHeartbeat > 120000)
            {
                EventTracker.getLogger().error("[PS2 PS4-US] No hearbeat message received for > 5 minutes. Restarting websocket connection.");
                disconnectWebsocket();
            }

            //If the current connection attempt has lasted longer than a minute, cancel the attempt and try again.
            else if(websocketConnectState.equals(WebsocketConnectState.CONNECTING) && startTime != 0 && (new Date().getTime()) - startTime > 60000)
            {
                EventTracker.getLogger().error("[PS2 PS4-US] Websocket Connection Timeout Reached. Retrying connection...");
                disconnectWebsocket();
            }
        });

        connectWebsocket();
    }
    
    //================================================================================
    // Websocket Connection Management
    //================================================================================
    
    private void connectWebsocket()
    {
        websocketConnectState = WebsocketConnectState.CONNECTING;
        startTime = new Date().getTime();
        
        client.websocket("/streaming?environment=ps2ps4us&service-id=s:" + config.getSoeServiceID(), ws ->
        {
            websocket = ws;
            websocket.handler(data ->
            {
                //We received a valid message from census.
                JsonObject message = new JsonObject(data.toString());
                String serviceType = message.getString("type");

                if (message.containsKey("connected") && message.getString("connected").equals("true"))
                {
                    //We are now connected.
                    //Set our connection state to open.
                    websocketConnectState = WebsocketConnectState.OPEN;
                    EventTracker.getLogger().info("[PS2 PS4-US] Websocket Secure Connection established to push.planetside.com");

                    //Get recent character ID's for population.
                    EventTracker.getLogger().info("[PS2 PS4-US] Requesting seen Character IDs...");
                    websocket.writeFinalTextFrame("{\"service\":\"event\", \"action\":\"recentCharacterIds\"}");
                }

                else if (message.containsKey("subscription"))
                {
                    EventTracker.getLogger().info("[PS2 PS4-US] Census Confirmed event feed subscription:");
                    EventTracker.getLogger().info(message.encodePrettily());
                }

                else if (serviceType != null)
                {
                    switch (serviceType)
                    {
                        case "serviceStateChanged":
                        {
                            if (message.getString("online").equals("true"))
                            {
                                for(String worldID : Utils.getWorldIDsFromEndpointString(message.getString("detail")))
                                {
                                    updateEndpointStatus(worldID, true);
                                }
                            }

                            else
                            {
                                for(String worldID : Utils.getWorldIDsFromEndpointString(message.getString("detail")))
                                {
                                    updateEndpointStatus(worldID, false);
                                }
                            }

                            break;
                        }
                        case "heartbeat":
                        {
                            lastHeartbeat = new Date().getTime();
                            JsonObject onlineList = message.getJsonObject("online");

                            for (Entry<String, Object> endpoint : onlineList)
                            {
                                if (endpoint.getValue().equals("true"))
                                {
                                    for(String worldID : Utils.getWorldIDsFromEndpointString(endpoint.getKey()))
                                    {
                                        updateEndpointStatus(worldID, true);
                                    }
                                }

                                else
                                {
                                    for(String worldID : Utils.getWorldIDsFromEndpointString(endpoint.getKey()))
                                    {
                                        updateEndpointStatus(worldID, false);
                                    }
                                }
                            }

                            break;
                        }
                        case "serviceMessage":
                        {
                            JsonObject payload = message.getJsonObject("payload");
                            String eventName = payload.getString("event_name");

                            processServiceMessage(payload, eventName);
                            break;
                        }
                        default:
                        {
                            EventTracker.getLogger().warn("[PS2 PS4-US] Could not handle message!");
                            EventTracker.getLogger().warn(message.encodePrettily());
                            break;
                        }
                    }
                }

                else if (!message.containsKey("send this for help"))
                {
                    EventTracker.getLogger().warn("[PS2 PS4-US] Could not handle message!");
                    EventTracker.getLogger().warn(message.encodePrettily());
                }
            });

            //Disconnects/Reconnects the websocket when the connection closes.
            websocket.closeHandler(v ->
            {
                EventTracker.getLogger().error("[PS2 PS4-US] Websocket connection lost: The websocket connection was closed.");
                disconnectWebsocket();
            });

            //Disconnects/Reconnects the websocket when the connection "gracefully" ends.
            websocket.endHandler(v ->
            {
                EventTracker.getLogger().error("[PS2 PS4-US] Websocket connection lost: The websocket connection ended.");
                disconnectWebsocket();
            });
            
            //Disconnects/Reconnects the websocket if we receive an exception.
            websocket.exceptionHandler(e ->
            {
                EventTracker.getLogger().error("[PS2 PS4-US] Websocket connection lost: A fatal connection exception occured. (See below for stack trace)");
                disconnectWebsocket();
                e.printStackTrace();
            });
        });
    }
    
    private void disconnectWebsocket()
    {
        //Close the websocket if not already, and set it to null to free unused memory.
        if(websocket != null)
        {
            try
            {
                websocket.close();
            }
            catch(Exception e)
            {
                
            }
            finally
            {
                websocket = null;
            }
        }
        
        //Set the connection state to closed
        websocketConnectState = WebsocketConnectState.CLOSED;

        //Update endpoints if they are not already offline.
        if(managedWorlds.isEmpty())
        {
            for (WorldInfo world : EventTracker.getDynamicDataManager().getAllWorldInfo().values())
            {
                world.setOnline(false);
            }
        }
        else
        {
            for(World world : managedWorlds)
            {
                EventTracker.getDynamicDataManager().getWorldInfo(world).setOnline(false);
            }
        }
    }
    
    //================================================================================
    // Message Management
    //================================================================================

    private void processServiceMessage(JsonObject payload, String eventName)
    {
        //This is the final init step. Process the character list.
        if (payload.containsKey("recent_character_id_list"))
        {
            EventTracker.getLogger().info("[PS2 PS4-US] Character List Received!");
            EventTracker.getLogger().info("[PS2 PS4-US] Subscribing to all events...");

            //Send subscription message
            websocket.writeFinalTextFrame("{\"service\": \"event\",\"action\": \"subscribe\",\"characters\": [\"all\"],\"worlds\": [\"all\"],\"eventNames\": [\"all\"]}");

            JsonArray recentCharacterIDList = payload.getJsonArray("recent_character_id_list");
            for (int i = 0; i < recentCharacterIDList.size(); i++)
            {
                JsonObject characterPayload = new JsonObject();
                characterPayload.put("character_id", recentCharacterIDList.getString(i));
                characterPayload.put("event_name", "CharacterList");

                EventTracker.getEventHandler().handleEvent("CharacterList", characterPayload, Environment.PS4_US);
            }
        }

        //This is a regular event.
        //Don't send this event if the world is not online, otherwise send it to the event manage for processing.
        else if (EventTracker.getDynamicDataManager().getWorldInfo(World.getWorldByID(payload.getString("world_id"))).isOnline())
        {
            EventTracker.getEventHandler().handleEvent(eventName, payload, Environment.PS4_US);
        }
    }
    
    private void updateEndpointStatus(String worldID, Boolean newValue)
    {
        Boolean currentServerStatus = false;
        World world = World.getWorldByID(worldID);
        
        if(!managedWorlds.contains(world))
        {
            managedWorlds.add(world);
        }

        if (EventTracker.getDynamicDataManager().getWorldInfo(World.getWorldByID(worldID)) != null)
        {
            currentServerStatus = EventTracker.getDynamicDataManager().getWorldInfo(World.getWorldByID(worldID)).isOnline();
        }

        if (!currentServerStatus.equals(newValue))
        {
            if (newValue)
            {
	    	//Data is (now) being received for this world.
                //Query Census for World Data.
                queryManager.queryWorld(worldID, Environment.PS4_US);
            }

            else
            {
                //No data is being received from this feed. Cached data for this world is invalidated, and must be updated.
                EventTracker.getDynamicDataManager().getWorldInfo(world).setOnline(false);
            }
        }
    }
}