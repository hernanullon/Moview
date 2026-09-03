package com.unicamp.moview_v1;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.core.util.Pair;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Helper SQLite con soporte para:
 * - Inserción async.
 * - Lectura del PRIMER registro (legacy).
 * - NUEVO: Lectura de los PRIMEROS N registros (batch) sin borrarlos.
 * - NUEVO: Borrado de múltiples IDs en una sola transacción (troceado si son muchos).
 */
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

    // Un solo executor serial para no pelear con SQLite
    private ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SQLite-JSON-Helper");
        t.setDaemon(true);
        return t;
    });

    public JSONDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) { db.execSQL(CREATE_TABLE); }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    private void ensureExecutor() {
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            executor = Executors.newSingleThreadExecutor();
        }
    }

    // =========================
    // Inserción
    // =========================
    public Future<?> insertJson(String jsonData) {
        ensureExecutor();
        return executor.submit(() -> {
            SQLiteDatabase db = this.getWritableDatabase();
            try {
                ContentValues values = new ContentValues();
                values.put(COLUMN_JSON, jsonData);
                db.insert(TABLE_NAME, null, values);
            } finally {
                db.close();
            }
        });
    }

    // =========================
    // Legacy: primer registro
    // =========================
    public Future<Pair<JSONObject, Integer>> getFirstJsonWithId() {
        ensureExecutor();
        return executor.submit(() -> {
            Pair<JSONObject, Integer> result = null;
            String selectQuery = "SELECT " + COLUMN_ID + ", " + COLUMN_JSON +
                    " FROM " + TABLE_NAME +
                    " ORDER BY " + COLUMN_ID + " ASC LIMIT 1";

            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.rawQuery(selectQuery, null);
            try {
                if (cursor.moveToFirst()) {
                    @SuppressLint("Range") String jsonData = cursor.getString(cursor.getColumnIndex(COLUMN_JSON));
                    @SuppressLint("Range") int id = cursor.getInt(cursor.getColumnIndex(COLUMN_ID));
                    try {
                        JSONObject jsonObject = new JSONObject(jsonData);
                        result = new Pair<>(jsonObject, id);
                    } catch (JSONException ignored) {}
                }
            } finally {
                cursor.close();
                db.close();
            }
            return result;
        });
    }

    public void deleteRecordById(int id) {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            db.delete(TABLE_NAME, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
        } finally {
            db.close();
        }
    }

    public Future<Integer> getJsonCount() {
        ensureExecutor();
        return executor.submit(() -> {
            String countQuery = "SELECT COUNT(*) FROM " + TABLE_NAME;
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.rawQuery(countQuery, null);
            try {
                cursor.moveToFirst();
                return cursor.getInt(0);
            } finally {
                cursor.close();
                db.close();
            }
        });
    }

    // =========================
    // NUEVO: Leer los primeros N (batch)
    // =========================
    public Future<List<Pair<JSONObject, Integer>>> getNextNJsonWithId(int limit) {
        ensureExecutor();
        return executor.submit(() -> {
            List<Pair<JSONObject, Integer>> result = new ArrayList<>();
            if (limit <= 0) return result;

            // Nota: en Android, parametrizar LIMIT con "?" puede no aplicar; concatenamos con cuidado.
            String selectQuery = "SELECT " + COLUMN_ID + ", " + COLUMN_JSON +
                    " FROM " + TABLE_NAME +
                    " ORDER BY " + COLUMN_ID + " DESC LIMIT " + limit;

            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.rawQuery(selectQuery, null);
            try {
                while (cursor.moveToNext()) {
                    int id = cursor.getInt(0);
                    String jsonData = cursor.getString(1);
                    try {
                        JSONObject json = new JSONObject(jsonData);
                        result.add(new Pair<>(json, id));
                    } catch (JSONException ignored) {}
                }
            } finally {
                cursor.close();
                db.close();
            }
            return result;
        });
    }

    // =========================
    // NUEVO: Borrar múltiples IDs (transacción + troceo)
    // =========================
    public void deleteRecordsByIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) return;

        // SQLite tiene límites de cantidad de parámetros; troceamos (p.ej., 500)
        final int CHUNK = 500;
        int from = 0;

        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            while (from < ids.size()) {
                int to = Math.min(from + CHUNK, ids.size());
                List<Integer> sub = ids.subList(from, to);

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sub.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append('?');
                }
                String sql = "DELETE FROM " + TABLE_NAME + " WHERE " + COLUMN_ID + " IN (" + sb + ")";

                String[] args = new String[sub.size()];
                for (int i = 0; i < sub.size(); i++) {
                    args[i] = String.valueOf(sub.get(i));
                }

                db.execSQL(sql, args);
                from = to;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    public void closeExecutor() {
        if (executor != null) executor.shutdown();
    }
}



//
//package com.unicamp.moview_v1;
//
//import android.annotation.SuppressLint;
//import android.content.ContentValues;
//import android.content.Context;
//import android.content.SharedPreferences;
//import android.database.Cursor;
//import android.database.sqlite.SQLiteDatabase;
//import android.database.sqlite.SQLiteOpenHelper;
//
//import androidx.core.util.Pair;
//
//import org.json.JSONException;
//import org.json.JSONObject;
//
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.Future;
//
//public class JSONDatabaseHelper extends SQLiteOpenHelper {
//    private static final int DATABASE_VERSION = 1;
//    private static final String DATABASE_NAME = "json_db";
//    private static final String TABLE_NAME = "json_table";
//    private static final String COLUMN_ID = "id";
//    private static final String COLUMN_JSON = "json_data";
//    private static final String CREATE_TABLE =
//            "CREATE TABLE " + TABLE_NAME + "(" +
//                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
//                    COLUMN_JSON + " TEXT" + ")";
//
//    private ExecutorService executor = Executors.newSingleThreadExecutor();
//
//    public JSONDatabaseHelper(Context context) {
//        super(context, DATABASE_NAME, null, DATABASE_VERSION);
//    }
//
//    @Override
//    public void onCreate(SQLiteDatabase db) {
//        db.execSQL(CREATE_TABLE);
//    }
//
//    @Override
//    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
//        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
//        onCreate(db);
//    }
//
//    public Future<?> insertJson(String jsonData) {
//        return executor.submit(() -> {
//            SQLiteDatabase db = this.getWritableDatabase();
//            ContentValues values = new ContentValues();
//            values.put(COLUMN_JSON, jsonData);
//            db.insert(TABLE_NAME, null, values);
//            db.close();
//        });
//    }
//
//    private void ensureExecutor() {
//        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
//            executor = Executors.newSingleThreadExecutor();
//        }
//    }
//
//
//    public Future<Pair<JSONObject, Integer>> getFirstJsonWithId() {
//        ensureExecutor();
//        return executor.submit(() -> {
//            Pair<JSONObject, Integer> result = null;
//            String selectQuery = "SELECT * FROM " + TABLE_NAME + " ORDER BY " + COLUMN_ID + " ASC LIMIT 1";
//            SQLiteDatabase db = this.getReadableDatabase();
//            Cursor cursor = db.rawQuery(selectQuery, null);
//
//            if (cursor.moveToFirst()) {
//                @SuppressLint("Range") String jsonData = cursor.getString(cursor.getColumnIndex(COLUMN_JSON));
//                @SuppressLint("Range") int id = cursor.getInt(cursor.getColumnIndex(COLUMN_ID));
//                try {
//                    JSONObject jsonObject = new JSONObject(jsonData);
//                    result = new Pair<>(jsonObject, id);
//                } catch (JSONException e) {
//                    e.printStackTrace();
//                }
//            }
//
//            cursor.close();
//            db.close();
//            return result;
//        });
//    }
//
//    public void deleteRecordById(int id) {
//        SQLiteDatabase db = this.getWritableDatabase();
//        db.delete(TABLE_NAME, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
//        db.close();
//    }
//
//
//    public Future<Integer> getJsonCount() {
//        return executor.submit(() -> {
//            String countQuery = "SELECT COUNT(*) FROM " + TABLE_NAME;
//            SQLiteDatabase db = this.getReadableDatabase();
//            Cursor cursor = db.rawQuery(countQuery, null);
//            cursor.moveToFirst();
//            int count = cursor.getInt(0);
//            cursor.close();
//            db.close();
//            return count;
//        });
//    }
//
//    public void closeExecutor() {
//        executor.shutdown();
//    }
//
//}
//




// BORRAR BASE DE DATOS EN CELULAR
//        SharedPreferences prefs = context.getSharedPreferences("my_prefs", Context.MODE_PRIVATE);
//        boolean alreadyCleaned = prefs.getBoolean("already_cleaned", false);
//
//        if (!alreadyCleaned) {
//            SQLiteDatabase db = getWritableDatabase();
//            db.execSQL("DELETE FROM " + TABLE_NAME);
//            // Si necesitas reiniciar el AUTOINCREMENT, también puedes hacerlo
//            db.execSQL("DELETE FROM SQLITE_SEQUENCE WHERE NAME = '" + TABLE_NAME + "'");
//            db.close();
//
//            SharedPreferences.Editor editor = prefs.edit();
//            editor.putBoolean("already_cleaned", true);
//            editor.apply();
//        }