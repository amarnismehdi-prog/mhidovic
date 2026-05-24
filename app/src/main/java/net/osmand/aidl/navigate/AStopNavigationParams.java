package net.osmand.aidl.navigate;

import android.os.Parcel;
import android.os.Parcelable;

public class AStopNavigationParams implements Parcelable {

    public AStopNavigationParams() {}

    protected AStopNavigationParams(Parcel in) {}

    @Override
    public void writeToParcel(Parcel dest, int flags) {}

    @Override
    public int describeContents() { return 0; }

    public static final Creator<AStopNavigationParams> CREATOR = new Creator<AStopNavigationParams>() {
        @Override
        public AStopNavigationParams createFromParcel(Parcel in) { return new AStopNavigationParams(in); }

        @Override
        public AStopNavigationParams[] newArray(int size) { return new AStopNavigationParams[size]; }
    };
}
