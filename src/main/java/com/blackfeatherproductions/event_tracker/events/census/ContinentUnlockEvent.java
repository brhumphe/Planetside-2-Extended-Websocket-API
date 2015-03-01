package com.blackfeatherproductions.event_tracker.events.census;

import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import com.blackfeatherproductions.event_tracker.DynamicDataManager;
import com.blackfeatherproductions.event_tracker.EventTracker;
import com.blackfeatherproductions.event_tracker.data_static.Faction;
import com.blackfeatherproductions.event_tracker.data_static.World;
import com.blackfeatherproductions.event_tracker.data_static.Zone;
import com.blackfeatherproductions.event_tracker.events.Event;
import com.blackfeatherproductions.event_tracker.events.EventInfo;
import com.blackfeatherproductions.event_tracker.events.EventPriority;

@EventInfo(eventName="ContinentUnlock",
listenedEvents = "ContinentUnlock",
priority = EventPriority.LOWEST,
filters = { "factions", "zones", "worlds" })
public class ContinentUnlockEvent implements Event
{
	private DynamicDataManager dynamicDataManager = EventTracker.getInstance().getDynamicDataManager();
	
	private JsonObject payload;
	
	@Override
	public void preProcessEvent(JsonObject payload)
	{
		this.payload = payload;
		if(payload != null)
		{
			processEvent();
		}
	}

	@Override
	public void processEvent()
	{
		//Data
		String vs_population = payload.getString("vs_population");
		String nc_population = payload.getString("nc_population");
		String tr_population = payload.getString("tr_population");
		
		Faction unlocked_by = Faction.getFactionByID(payload.getString("triggering_faction"));
		String metagame_event_id = payload.getString("metagame_event_id");
		
		String timestamp = payload.getString("timestamp");
		Zone zone = Zone.getZoneByID(payload.getString("zone_id"));
		World world = World.getWorldByID(payload.getString("world_id"));
		
		//Update Internal Data
		dynamicDataManager.getWorldInfo(world).getZoneInfo(zone).setLocked(false);
		dynamicDataManager.getWorldInfo(world).getZoneInfo(zone).setLockingFaction(Faction.NS);
		
		//Payload
		JsonObject eventData = new JsonObject();
		
		eventData.putString("vs_population", vs_population);
		eventData.putString("nc_population", nc_population);
		eventData.putString("tr_population", tr_population);
		
		eventData.putString("unlocked_by", unlocked_by.getID());
		eventData.putString("metagame_event_id", metagame_event_id);
		
		eventData.putString("timestamp", timestamp);
		eventData.putString("zone_id", zone.getID());
		eventData.putString("world_id", world.getID());
		
		//Filters
		JsonObject filterData = new JsonObject();
		
		filterData.putArray("factions", new JsonArray().addString(unlocked_by.getID()));
		filterData.putArray("zones", new JsonArray().addString(zone.getID()));
		filterData.putArray("worlds", new JsonArray().addString(world.getID()));
		
		//Broadcast Event
		JsonObject message = new JsonObject();
		
		message.putObject("event_data", eventData);
		message.putObject("filter_data", filterData);
		
		EventTracker.getInstance().getEventServer().BroadcastEvent(this.getClass(), message);
    	EventTracker.getInstance().countProcessedEvent();
	}
}