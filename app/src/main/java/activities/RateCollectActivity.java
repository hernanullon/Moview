package activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.unicamp.moview_v1.MainActivity;
import com.unicamp.moview_v1.R;

public class RateCollectActivity extends AppCompatActivity {
    private EditText editTextInertialOFF, editTextLocationOFF, editTextCANOFF, editTextClimaticOFF;
    private SwitchCompat swInertialRT, swLocationRT, swCANRT, swClimaticRT;
    private Spinner spInertialOFF, spLocationOFF, spCANOFF, spClimaticOFF;
    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.rate_collection);

        editTextInertialOFF = findViewById(R.id.editText_inertial_OFF);
        editTextLocationOFF = findViewById(R.id.editText_Location_OFF);
        editTextCANOFF = findViewById(R.id.editText_Can_OFF);
        editTextClimaticOFF = findViewById(R.id.editText_Climatic_OFF);

        spInertialOFF = findViewById(R.id.spinnerInertial_RT);
        spLocationOFF = findViewById(R.id.spinnerLocation_RT);
        spCANOFF = findViewById(R.id.spinnerCAN_RT);
        spClimaticOFF = findViewById(R.id.spinnerClimatic_RT);

        swInertialRT = findViewById(R.id.sw_inertial_RT);
        swLocationRT = findViewById(R.id.sw_location_RT);
        swCANRT = findViewById(R.id.sw_CAN_RT);
        swClimaticRT = findViewById(R.id.sw_climatic_RT);

        sharedPreferences = getSharedPreferences("Config", MODE_PRIVATE);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.time_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        int[] spinnerIds = {R.id.spinnerInertial_RT, R.id.spinnerLocation_RT,R.id.spinnerCAN_RT, R.id.spinnerClimatic_RT};
        for (int spinnerId : spinnerIds) {
            Spinner spinner = findViewById(spinnerId);
            spinner.setAdapter(adapter);
        }

        Button buttonNextActivity = findViewById(R.id.btn_cont_parameters);
        buttonNextActivity.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                saveValues();
                startNextActivity();
            }
        });

        Button buttonLastActivity = findViewById(R.id.btn_back_parameters);
        buttonLastActivity.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startLastActivity();
            }
        });
    }

    private void startNextActivity() {
        Intent intent = new Intent(RateCollectActivity.this, MainActivity.class);
        startActivity(intent);
    }

    private void startLastActivity() {
        Intent intent = new Intent(RateCollectActivity.this, EnergyTempActivity.class);
        startActivity(intent);
    }

    private void saveValues() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        try {
            editor.putInt("InertialRate", convert_rate(spInertialOFF,editTextInertialOFF));
            editor.putInt("LocationRate", convert_rate(spLocationOFF,editTextLocationOFF));
            editor.putInt("CANRate", convert_rate(spCANOFF,editTextCANOFF));
            editor.putInt("ClimaticRate", convert_rate(spClimaticOFF,editTextClimaticOFF));

            editor.putBoolean("InertialRT", swInertialRT.isChecked());
            editor.putBoolean("LocationRT", swLocationRT.isChecked());
            editor.putBoolean("CANRT", swCANRT.isChecked());
            editor.putBoolean("ClimaticRT", swClimaticRT.isChecked());

            editor.apply();
        }catch (Exception e){
            Log.d("TAG", "Error");
        }
    }

    public int convert_rate(Spinner sp, EditText et){
        if(sp.getSelectedItem().equals("ms")){
            return Integer.parseInt(et.getText().toString());
        }
        else if(sp.getSelectedItem().equals("s")){
            return Integer.parseInt(et.getText().toString())*1000;
        }
        else{
            return Integer.parseInt(et.getText().toString())*60000;
        }
    }

}
