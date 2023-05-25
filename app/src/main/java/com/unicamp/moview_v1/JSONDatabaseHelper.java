package com.unicamp.moview_v1;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class JSONDatabaseHelper extends SQLiteOpenHelper {

    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "json_db";
    private static final String TABLE_NAME = "json_table";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_JSON = "json_data";
    private static final String CREATE_TABLE =
            "CREATE TABLE " + TABLE_NAME + "(" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COLUMN_JSON + " TEXT" + ")";

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    public JSONDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    public Future<?> insertJson(String jsonData) {
        return executor.submit(() -> {
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COLUMN_JSON, jsonData);
            db.insert(TABLE_NAME, null, values);
            db.close();
        });
    }

    public Future<JSONObject> getLastJson() {
        return executor.submit(() -> {
            JSONObject jsonObject = null;
            String selectQuery = "SELECT * FROM " + TABLE_NAME + " ORDER BY " + COLUMN_ID + " DESC LIMIT 1";
            SQLiteDatabase db = this.getWritableDatabase();
            Cursor cursor = db.rawQuery(selectQuery, null);

            if (cursor.moveToFirst()) {
                @SuppressLint("Range") String jsonData = cursor.getString(cursor.getColumnIndex(COLUMN_JSON));
                try {
                    jsonObject = new JSONObject(jsonData);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                @SuppressLint("Range") int id = cursor.getInt(cursor.getColumnIndex(COLUMN_ID));
                db.delete(TABLE_NAME, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
            }
            cursor.close();
            db.close();
            return jsonObject;
        });
    }

    public Future<Integer> getJsonCount() {
        return executor.submit(() -> {
            String countQuery = "SELECT COUNT(*) FROM " + TABLE_NAME;
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.rawQuery(countQuery, null);
            cursor.moveToFirst();
            int count = cursor.getInt(0);
            cursor.close();
            db.close();
            return count;
        });
    }

    public void closeExecutor() {
        executor.shutdown();
    }

}
