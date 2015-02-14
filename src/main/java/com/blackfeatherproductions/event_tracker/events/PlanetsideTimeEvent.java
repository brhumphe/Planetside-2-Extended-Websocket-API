package com.blackfeatherproductions.event_tracker.events;

import org.vertx.java.core.json.JsonObject;

import com.blackfeatherproductions.event_tracker.EventTracker;

@EventInfo(eventNames = "PlanetsideTime", priority = EventPriority.NORMAL)
public class PlanetsideTimeEvent implements Event
{
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
		JsonObject eventData = new JsonObject();
		
		//Event Specific Data
		eventData.putString("old_time", payload.getString("old_time"));
		eventData.putString("new_time", payload.getString("new_time"));
		eventData.putString("diff", payload.getString("diff"));
		
		JsonObject message = new JsonObject();
		message.putObject("event_data", eventData);
		message.putString("event_type","PlanetsideTime");
		
		EventTracker.getInstance().getEventServer().BroadcastEvent(message);
	}
}