package com.blackfeatherproductions.EventTracker.events;

import org.vertx.java.core.json.JsonObject;


@EventInfo(eventNames = "MetagameEvent")
public class MetagameEvent implements Event
{
    @Override
    public void processEvent(JsonObject payload)
    {
     // TODO Auto-generated method stub
    }

    @Override
    public JsonObject getBlankSubscription()
    {
        // TODO Auto-generated method stub
        return null;
    }
}
