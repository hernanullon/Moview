package com.unicamp.moview_v1;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

import activities.EnergyTempActivity;

public class ConfigureParametersActivity extends AppCompatActivity {

    private EditText editTextTimeInit;
    private EditText editTextTimeFinish;
    private EditText datePickerHoliday;
    private ArrayList<String> selectedDates = new ArrayList<>();
    private Set<String> selectedCheckBoxes = new HashSet<>();
    SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);

        sharedPreferences = getSharedPreferences("Config", MODE_PRIVATE);
        int[] checkBoxIds = {
                R.id.checkBoxSunday,
                R.id.checkBoxMonday,
                R.id.checkBoxTuesday,
                R.id.checkBoxWednesday,
                R.id.checkBoxThursday,
                R.id.checkBoxFriday,
                R.id.checkBoxSaturday
        };

        for (int checkBoxId : checkBoxIds) {
            CheckBox checkBox = findViewById(checkBoxId);
            checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    String checkBoxTag = buttonView.getTag().toString();
                    updateSelectedCheckBoxes(checkBoxTag, isChecked);
                }
            });
        }

        editTextTimeInit = findViewById(R.id.editTextTimeInit);
        editTextTimeInit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTimePicker(editTextTimeInit);
            }
        });

        editTextTimeFinish = findViewById(R.id.editTextTimeFinish);
        editTextTimeFinish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTimePicker(editTextTimeFinish);
            }
        });

        datePickerHoliday = findViewById(R.id.datePickerEditText);
        datePickerHoliday.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDatePicker();
            }
        });

        Button buttonStartActivity = findViewById(R.id.btn_cont_time);
        buttonStartActivity.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                if (!editTextTimeInit.getText().toString().isEmpty()){
                    editor.putString("timeInit", editTextTimeInit.getText().toString());
                }
                if (!editTextTimeFinish.getText().toString().isEmpty()){
                    editor.putString("timeFinish", editTextTimeFinish.getText().toString());
                }
                editor.putStringSet("selectedDates", new HashSet<>(selectedDates));
                editor.putStringSet("selectedDays", selectedCheckBoxes);
                editor.apply();
                startNextActivity();
            }
        });


        Button buttonAdd = findViewById(R.id.btn_back_time2);
        buttonAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String date = datePickerHoliday.getText().toString();
                selectedDates.add(date);
                Toast.makeText(ConfigureParametersActivity.this, "Fecha agregada: " + date, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startNextActivity() {
        Intent intent = new Intent(ConfigureParametersActivity.this, EnergyTempActivity.class);
        startActivity(intent);
    }

    private void updateSelectedCheckBoxes(String checkBoxId, boolean isChecked) {
        if (isChecked) {
            selectedCheckBoxes.add(checkBoxId);
        } else {
            selectedCheckBoxes.remove(checkBoxId);
        }
    }

    private void openDatePicker() {
        Calendar calendar = Calendar.getInstance();
        int currentYear = calendar.get(Calendar.YEAR);
        int currentMonth = calendar.get(Calendar.MONTH);
        int currentDay = calendar.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog datePickerDialog = new DatePickerDialog(
                ConfigureParametersActivity.this,
                new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                        month += 1;
                        datePickerHoliday.setText(String.format("%04d-%02d-%02d", year, month, dayOfMonth));
                    }
                },
                currentYear,
                currentMonth,
                currentDay
        );
        datePickerDialog.show();
    }

    private void openTimePicker(EditText targetEditText) {
        Calendar calendar = Calendar.getInstance();
        int currentHour = calendar.get(Calendar.HOUR_OF_DAY);
        int currentMinute = calendar.get(Calendar.MINUTE);

        TimePickerDialog timePickerDialog = new TimePickerDialog(
                ConfigureParametersActivity.this,
                new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                        targetEditText.setText(String.format("%02d:%02d", hourOfDay, minute));
                    }
                },
                currentHour,
                currentMinute,
                true
        );
        timePickerDialog.show();
    }
}
