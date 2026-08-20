package com.moneyai.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public final class MoneyStore {
    private static final String PREFS = "money_ai";
    private static final String KEY = "transactions";

    public static final class Tx {
        public final long amount;
        public final String direction;
        public final String merchant;
        public final String category;
        public final long timestamp;

        public Tx(long amount, String direction, String merchant, String category, long timestamp) {
            this.amount = amount;
            this.direction = direction;
            this.merchant = merchant;
            this.category = category;
            this.timestamp = timestamp;
        }
    }

    private MoneyStore() {}

    public static synchronized void add(Context context, Tx tx) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(KEY, "[]"));
            JSONObject o = new JSONObject();
            o.put("amount", tx.amount);
            o.put("direction", tx.direction);
            o.put("merchant", tx.merchant);
            o.put("category", tx.category);
            o.put("timestamp", tx.timestamp);
            arr.put(o);
            p.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized List<Tx> all(Context context) {
        List<Tx> out = new ArrayList<>();
        try {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Tx(o.getLong("amount"), o.getString("direction"), o.getString("merchant"), o.getString("category"), o.getLong("timestamp")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static long sum(Context context, String direction, boolean todayOnly) {
        long start;
        Calendar c = Calendar.getInstance();
        if (todayOnly) {
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        } else {
            c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        }
        start = c.getTimeInMillis();
        long sum = 0;
        for (Tx tx : all(context)) if (direction.equals(tx.direction) && tx.timestamp >= start) sum += tx.amount;
        return sum;
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}
