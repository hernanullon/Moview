package activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.unicamp.moview_v1.R;

public class EnergyTempActivity extends AppCompatActivity {
    private EditText editTextTemperatureMin;
    private EditText editTextTemperatureMax;
    private EditText editTextBatteryMin;
    private EditText editTextBatteryMax;
    private EditText editTextLatitude;
    private EditText editTextLongitude;
    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.cellphone_configurations);

        editTextTemperatureMin = findViewById(R.id.editTextTemperatureMin);
        editTextTemperatureMax = findViewById(R.id.editTextTemperatureMax);
        editTextBatteryMin = findViewById(R.id.editTextBatteryMin);
        editTextBatteryMax = findViewById(R.id.editTextBatteryMax);
        editTextLatitude = findViewById(R.id.editTextLatitude);
        editTextLongitude = findViewById(R.id.editTextLongitude);

        sharedPreferences = getSharedPreferences("Config", MODE_PRIVATE);

        Button buttonNextActivity = findViewById(R.id.btn_cont_energy);
        buttonNextActivity.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                saveValues();
                startNextActivity();
            }
        });

        Button buttonLastActivity = findViewById(R.id.btn_back_energy);
        buttonLastActivity.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startLastActivity();
            }
        });
    }

    private void startNextActivity() {
        Intent intent = new Intent(EnergyTempActivity.this, RateCollectActivity.class);
        startActivity(intent);
    }

    private void startLastActivity() {
        Intent intent = new Intent(EnergyTempActivity.this, ConfigureParametersActivity.class);
        startActivity(intent);
    }


    private boolean validateValues() {
        int temperatureMin = Integer.parseInt(editTextTemperatureMin.getText().toString());
        int temperatureMax = Integer.parseInt(editTextTemperatureMax.getText().toString());
        int batteryMin = Integer.parseInt(editTextBatteryMin.getText().toString());
        int batteryMax = Integer.parseInt(editTextBatteryMax.getText().toString());
        if (temperatureMin < 0 || temperatureMin > 100)
            return false;
        if (temperatureMax < 0 || temperatureMax > 100)
            return false;
        if (temperatureMax <= temperatureMin)
            return false;
        if (batteryMin < 0 || batteryMin > 100)
            return false;
        if (batteryMax < 0 || batteryMax > 100)
            return false;
        if (batteryMax <= batteryMin)
            return false;
        return true;
    }

    private void saveValues() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        try {
            editor.putInt("temperatureMin", Integer.parseInt(editTextTemperatureMin.getText().toString()));
            editor.putInt("temperatureMax", Integer.parseInt(editTextTemperatureMax.getText().toString()));
            editor.putInt("batteryMin", Integer.parseInt(editTextBatteryMin.getText().toString()));
            editor.putInt("batteryMax", Integer.parseInt(editTextBatteryMax.getText().toString()));
            editor.putFloat("latitude", Float.parseFloat(editTextLatitude.getText().toString()));
            editor.putFloat("longitude", Float.parseFloat(editTextLongitude.getText().toString()));
            editor.apply();
        }catch (Exception e){
            Log.d("TAG", "Error");
        }
    }
}
