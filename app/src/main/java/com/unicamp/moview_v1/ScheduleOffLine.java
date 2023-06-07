package com.unicamp.moview_v1;

import android.location.Location;

import java.time.LocalTime;
import java.time.ZonedDateTime;

public class ScheduleOffLine {

    public void sendDataOffline(JSONDatabaseHelper buffer){
        buffer.getLastJson();

    }

    public boolean compareWorkTime(Location loc1,Location loc2,ZonedDateTime timeNow, LocalTime finalTimeWork, LocalTime initialTimeWork){
        float distance = loc1.distanceTo(loc2);
        if(distance <= 100) {
            if (timeNow.toLocalTime().withNano(0).isAfter(finalTimeWork.withNano(0)) &&
                    timeNow.toLocalTime().withNano(0).isBefore(initialTimeWork.withNano(0))) {
                return true;
            }
        }
        return false;
    }

}
