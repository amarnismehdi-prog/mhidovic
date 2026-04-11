package com.byd.myapp.dashboard;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.widget.TextView;

import com.byd.myapp.R;

/**
 * DashboardPresentation — implémentation Presentation API (NON UTILISÉE).
 *
 * Conservée comme référence de l'approche android.app.Presentation.
 * L'application utilise BYDDashboardActivity (Activity sur display 1) à la place.
 *
 * Le cluster BYD Seal n'apparaît pas comme un display DISPLAY_CATEGORY_PRESENTATION
 * dans DisplayManager — il est directement adressable via IActivityManager/displayId=1.
 */
public class DashboardPresentation extends Presentation {

    private TextView tvSpeed;
    private TextView tvBattery;
    private TextView tvRange;
    private TextView tvGear;

    public DashboardPresentation(Context context, Display display) {
        super(context, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.presentation_dashboard);

        tvSpeed   = (TextView) findViewById(R.id.dash_speed);
        tvBattery = (TextView) findViewById(R.id.dash_battery);
        tvRange   = (TextView) findViewById(R.id.dash_range);
        tvGear    = (TextView) findViewById(R.id.dash_gear);
    }

    public void updateSpeed(int speedKmh) {
        if (tvSpeed != null) {
            tvSpeed.setText(String.valueOf(speedKmh));
        }
    }

    public void updateBattery(int percent) {
        if (tvBattery != null) {
            tvBattery.setText(percent + "%");
        }
    }

    public void updateRange(int km) {
        if (tvRange != null) {
            tvRange.setText(km + " km");
        }
    }

    public void updateGear(String gear) {
        if (tvGear != null) {
            tvGear.setText(gear);
        }
    }
}
