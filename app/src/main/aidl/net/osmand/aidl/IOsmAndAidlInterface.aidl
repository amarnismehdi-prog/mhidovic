package net.osmand.aidl;

import net.osmand.aidl.navigate.AStopNavigationParams;

interface IOsmAndAidlInterface {
    boolean stopNavigation(in AStopNavigationParams params);
}
