package com.blackfeatherproductions.event_tracker.server;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import com.blackfeatherproductions.event_tracker.Config;
import com.blackfeatherproductions.event_tracker.EventTracker;
import com.blackfeatherproductions.event_tracker.MavenInfo;
import com.blackfeatherproductions.event_tracker.data_dynamic.WorldInfo;
import com.blackfeatherproductions.event_tracker.data_static.World;
import com.blackfeatherproductions.event_tracker.events.Event;
import com.blackfeatherproductions.event_tracker.events.EventInfo;
import com.blackfeatherproductions.event_tracker.events.EventType;
import com.blackfeatherproductions.event_tracker.server.actions.Action;
import com.blackfeatherproductions.event_tracker.server.actions.ActionInfo;
import com.blackfeatherproductions.event_tracker.server.actions.ActiveAlerts;
import com.blackfeatherproductions.event_tracker.server.actions.FacilityStatus;
import com.blackfeatherproductions.event_tracker.server.actions.ZoneStatus;

//TODO Make bad JSON more verbose for clients, and not throw exceptions.
//TODO Refactor "useAND" to "exclusive mode"
//TODO Implement subscription mode
//4am thoughts:
//4:14 AM - Jhett12321: how about if there are two things you pass to the subscription
//4:15 AM - Jhett12321: the first is a list of filters that the event must contain data for all (so if you pass it headshots, and worlds, it MUST be a headshot in one of the given worlds)
//4:16 AM - Jhett12321: the second is a list of filters that must contain at least one match (if you give it a zone and a world, it can be in that zone on any world, or any zone in that specific world)
//4:19 AM - Jhett12321: so the flow would be it would loop through the "must match" list, and check to see if all of the filters contain a match
//4:19 AM - Jhett12321: if those pass, it moves on to the "one match" list, and if any of those filters match it sends the event
public class EventServer
{
    private final Config config;

    //API Key Database
    private final String dbUrl;

    //Actions
    private final Map<ActionInfo, Class<? extends Action>> actions = new LinkedHashMap<ActionInfo, Class<? extends Action>>();

    //Client Info
    public final Map<ServerWebSocket, EventServerClient> clientConnections = new ConcurrentHashMap<ServerWebSocket, EventServerClient>();

    public EventServer()
    {
        config = EventTracker.getConfig();
        Vertx vertx = EventTracker.getVertx();

        //API Key Database
        dbUrl = "jdbc:mysql://" + config.getDbHost() + "/" + config.getDbName();

        //Client Actions
        registerActions();

        vertx.createHttpServer().websocketHandler(clientConnection ->
        {
            Map<String, String> queryPairs = new LinkedHashMap<String, String>();

            String query = clientConnection.query();
            String[] pairs = query.split("&");
            for (String pair : pairs)
            {
                int idx = pair.indexOf('=');
                try
                {
                    queryPairs.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"), URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                }
                catch (UnsupportedEncodingException e)
                {
                    e.printStackTrace();
                }
            }

            final String apiKey = queryPairs.get("apikey");

            final String apiName = verifyAPIKey(apiKey);
            if (apiName != null)
            {
                clientConnection.closeHandler(v ->
                {
                    clientConnections.remove(clientConnection);
                    EventTracker.getLogger().info("Client " + apiName + " Disconnected. API Key: " + apiKey);
                });

                clientConnection.exceptionHandler(e ->
                {
                    clientConnections.remove(clientConnection);
                    EventTracker.getLogger().info("Client " + apiName + " Disconnected. API Key: " + apiKey);
                });

                clientConnection.handler(data ->
                {
                    JsonObject message = null;

                    try
                    {
                        message = new JsonObject(data.toString());
                    }
                    catch (Exception e)
                    {
                        clientConnection.writeFinalTextFrame("{\"error\": \"BADJSON\", \"message\": \"You have supplied an invalid JSON string. Please check your syntax.\"}");
                    }

                    if (message != null)
                    {
                        EventTracker.getLogger().info("Client " + apiName + " Sent Valid JSON Message.");
                        EventTracker.getLogger().info(message.encodePrettily());
                        handleClientMessage(clientConnection, message);
                    }
                });

                clientConnections.put(clientConnection, new EventServerClient(clientConnection, apiKey, apiName));
                EventTracker.getLogger().info("Client " + apiName + " Connected! API Key: " + apiKey);

                //Send Connection Confirmed Message
                JsonObject connectMessage = new JsonObject();
                connectMessage.put("service", "ps2_events");
                connectMessage.put("version", MavenInfo.getVersion());
                connectMessage.put("websocket_event", "connectionStateChange");
                connectMessage.put("online", "true");

                clientConnection.writeFinalTextFrame(connectMessage.encode());

                //Send Service Status Messages
                for (Entry<World, WorldInfo> worldEntry : EventTracker.getDynamicDataManager().getAllWorldInfo().entrySet())
                {
                    JsonObject serviceMessage = new JsonObject();

                    JsonObject payload = new JsonObject();
                    payload.put("online", worldEntry.getValue().isOnline() ? "1" : "0");
                    payload.put("world_id", worldEntry.getKey().getID());

                    serviceMessage.put("payload", payload);
                    serviceMessage.put("event_type", "ServiceStateChange");
                    clientConnection.writeFinalTextFrame(serviceMessage.encode());
                }
            }
            else
            {
                clientConnection.reject();
            }
        }).listen(config.getServerPort());
    }

    private String verifyAPIKey(String apiKey)
    {
        if (apiKey != null && !apiKey.equals(""))
        {
            Connection dbConnection = null;

            try
            {
                Class.forName("com.mysql.jdbc.Driver");

                dbConnection = DriverManager.getConnection(dbUrl, config.getDbUser(), config.getDbPassword());

                PreparedStatement query = dbConnection.prepareStatement("SELECT * FROM APIKeys WHERE api_key = ? AND enabled = 1");
                query.setString(1, apiKey);
                ResultSet resultSet = query.executeQuery();

                //Check if API Key exists
                String apiName = null;
                if (resultSet.next())
                {
                    apiName = resultSet.getString("name");
                }

                query.close();
                dbConnection.close();

                return apiName;
            }

            catch (ClassNotFoundException | SQLException e)
            {
                e.printStackTrace();
            }
        }

        return null;
    }

    private void registerActions()
    {
        registerAction(ActiveAlerts.class);
        registerAction(ZoneStatus.class);
        registerAction(FacilityStatus.class);
    }

    private void handleClientMessage(ServerWebSocket clientConnection, JsonObject message)
    {
        String eventType = message.getString("event");
        String action = message.getString("action");

        message.remove("event");
        message.remove("action");

        if (!action.matches("subscribe|unsubscribe|unsubscribeall"))
        {
            handleAction(clientConnection, action, message);
        }

        else if (action.matches("subscribe|unsubscribe|unsubscribeall"))
        {
            clientConnections.get(clientConnection).handleSubscription(action, eventType, message);
        }

        else
        {
            clientConnection.writeFinalTextFrame("{\"error\": \"unknownAction\", \"message\": \"There is no Action by that name. Please check your syntax, and try again.\"}");
        }
    }

    private void handleAction(ServerWebSocket clientConnection, String actionName, JsonObject actionData)
    {
        for (Entry<ActionInfo, Class<? extends Action>> entry : actions.entrySet())
        {
            if (actionName.matches(entry.getKey().actionNames()))
            {
                try
                {
                    Action action = entry.getValue().newInstance();

                    action.processAction(clientConnection, actionData);
                }
                catch (InstantiationException | IllegalAccessException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }

    private void registerAction(Class<? extends Action> action)
    {
        ActionInfo info = action.getAnnotation(ActionInfo.class);
        if (info == null)
        {
            EventTracker.getLogger().warn("Implementing Action Class: " + action.getName() + " is missing a required annotation.");
            return;
        }

        actions.put(info, action);
    }

    public void broadcastEvent(Event event)
    {
        Class<? extends Event> eventClass = event.getClass();
        JsonObject messageToSend = new JsonObject();

        JsonObject eventFilterData = event.getFilterData();
        JsonObject eventData = event.getEventData();

        messageToSend.put("payload", eventData);
        messageToSend.put("event_type", eventClass.getAnnotation(EventInfo.class).eventName());
        messageToSend.put("environment", event.getEnvironment().toString().toLowerCase());

        for (Entry<ServerWebSocket, EventServerClient> connection : clientConnections.entrySet())
        {
            Boolean sendMessage = null;

            EventType eventType = eventClass.getAnnotation(EventInfo.class).eventType();

            if (eventType.equals(EventType.SERVICE))
            {
                sendMessage = true;
            }

            else if (eventType.equals(EventType.EVENT))
            {
                //Get Subscription for provided event
                JsonObject subscription = connection.getValue().getSubscription(eventClass);

                if (subscription.getString("all").equals("true"))
                {
                    sendMessage = true;
                }

                JsonArray envSubscription = subscription.getJsonArray("environments");
                if (envSubscription.size() > 0)
                {
                    sendMessage = false;

                    for (int i = 0; i < envSubscription.size(); i++)
                    {
                        if (event.getEnvironment().toString().equalsIgnoreCase(envSubscription.getString(i)))
                        {
                            sendMessage = true;
                        }
                    }
                }

                else
                {
                    for (String subscriptionProperty : subscription.fieldNames())
                    {
                        if (!subscriptionProperty.equals("all") && !subscriptionProperty.equals("useAND") && !subscriptionProperty.equals("show") && !subscriptionProperty.equals("hide"))
                        {
                            JsonArray filterData = eventFilterData.getJsonArray(subscriptionProperty);

                            if (subscriptionProperty.equals("worlds"))
                            {
                                JsonObject subscriptionValue = subscription.getJsonObject(subscriptionProperty);

                                if (subscriptionValue.size() > 0)
                                {
                                    if (subscriptionValue.containsKey(filterData.getString(0)))
                                    {
                                        JsonArray subscriptionZoneData = subscriptionValue.getJsonObject(filterData.getString(0)).getJsonArray("zones");
                                        JsonArray zoneData = eventFilterData.getJsonArray("zones");

                                        if (subscriptionZoneData == null || subscriptionZoneData.size() == 0 || subscriptionZoneData.contains(zoneData.getString(0)))
                                        {
                                            sendMessage = true;
                                        }

                                        else
                                        {
                                            sendMessage = false;
                                        }
                                    }

                                    else
                                    {
                                        sendMessage = false;
                                    }
                                }
                            }

                            else
                            {
                                JsonArray subscriptionValue = subscription.getJsonArray(subscriptionProperty);

                                if (subscriptionValue.size() > 0)
                                {
                                    if (subscription.getJsonArray("useAND").contains(subscriptionProperty))
                                    {
                                        for (int i = 0; i < filterData.size(); i++)
                                        {
                                            if (subscriptionValue.contains(filterData.getString(i)))
                                            {
                                                sendMessage = true;
                                            }
                                            else if (subscriptionValue.size() > 0)
                                            {
                                                sendMessage = false;
                                                break;
                                            }
                                        }
                                    }

                                    else
                                    {
                                        for (int i = 0; i < filterData.size(); i++)
                                        {
                                            if (subscriptionValue.contains(filterData.getString(i)))
                                            {
                                                sendMessage = true;
                                            }
                                            else if (subscriptionValue.size() > 0)
                                            {
                                                sendMessage = false;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }

                            if (sendMessage != null && !sendMessage)
                            {
                                break;
                            }
                        }
                    }
                }

                if (subscription.getJsonArray("show").size() > 0)
                {
                    JsonObject filteredPayload = new JsonObject();

                    for (String field : eventData.fieldNames())
                    {
                        if (subscription.getJsonArray("show").contains(field))
                        {
                            filteredPayload.put(field, eventData.getString(field));
                        }
                    }

                    messageToSend.put("payload", filteredPayload);
                }

                if (subscription.getJsonArray("hide").size() > 0)
                {
                    JsonObject filteredPayload = messageToSend.getJsonObject("payload");

                    for (String field : eventData.copy().fieldNames())
                    {
                        if (subscription.getJsonArray("hide").contains(field))
                        {
                            filteredPayload.remove(field);
                        }
                    }

                    messageToSend.put("payload", filteredPayload);
                }
            }

            if (sendMessage != null && sendMessage)
            {
                connection.getKey().writeFinalTextFrame(messageToSend.encode());
            }
        }
    }
}
