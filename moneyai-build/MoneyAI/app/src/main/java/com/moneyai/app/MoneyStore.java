package com.moneyai.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class MoneyStore {
    private static final String PREFS = "money_ai";
    private static final String KEY = "transactions";
    private static final String RULES = "merchant_category_rules";

    public static final class Tx {
        public final long amount;
        public final String direction;
        public final String merchant;
        public final String category;
        public final String paymentMethod;
        public final String source;
        public final long timestamp;

        public Tx(long amount, String direction, String merchant, String category,
                  String paymentMethod, String source, long timestamp) {
            this.amount = amount;
            this.direction = direction;
            this.merchant = merchant;
            this.category = category;
            this.paymentMethod = paymentMethod;
            this.source = source;
            this.timestamp = timestamp;
        }
    }

    private MoneyStore() {}

    public static synchronized void add(Context context, Tx tx) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(KEY, "[]"));

            // NotificationListener can receive the same notification again when it is updated.
            // Suppress obvious duplicates posted within 15 seconds.
            for (int i = arr.length() - 1; i >= 0 && i >= arr.length() - 12; i--) {
                JSONObject old = arr.optJSONObject(i);
                if (old == null || !old.has("paymentMethod")) continue;
                long t = old.optLong("timestamp", 0L);
                if (Math.abs(t - tx.timestamp) > 15000L) continue;
                if (old.optLong("amount", -1L) == tx.amount
                        && tx.direction.equals(old.optString("direction"))
                        && tx.paymentMethod.equals(old.optString("paymentMethod"))
                        && tx.merchant.equals(old.optString("merchant"))) {
                    return;
                }
            }

            JSONObject o = new JSONObject();
            o.put("schema", 2);
            o.put("amount", tx.amount);
            o.put("direction", tx.direction);
            o.put("merchant", tx.merchant);
            o.put("category", tx.category);
            o.put("paymentMethod", tx.paymentMethod);
            o.put("source", tx.source);
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
                // v0.1 Toss/stock-noise records did not have paymentMethod. Hide them.
                if (!o.has("paymentMethod")) continue;
                out.add(new Tx(
                        o.getLong("amount"),
                        o.getString("direction"),
                        o.getString("merchant"),
                        o.getString("category"),
                        o.getString("paymentMethod"),
                        o.optString("source", ""),
                        o.getLong("timestamp")
                ));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static long sum(Context context, String direction, boolean todayOnly) {
        long start = periodStart(todayOnly);
        long sum = 0;
        for (Tx tx : all(context)) {
            if (tx.timestamp < start) continue;
            if ("EXPENSE".equals(direction)) {
                if ("EXPENSE".equals(tx.direction)) sum += tx.amount;
                else if ("REFUND".equals(tx.direction)) sum -= tx.amount;
            } else if (direction.equals(tx.direction)) {
                sum += tx.amount;
            }
        }
        return Math.max(sum, 0L);
    }

    public static long monthStart() { return periodStart(false); }

    private static long periodStart(boolean todayOnly) {
        Calendar c = Calendar.getInstance();
        if (todayOnly) {
            c.set(Calendar.HOUR_OF_DAY, 0);
        } else {
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
        }
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static synchronized String learnedCategory(Context context, String merchant) {
        try {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RULES, "{}");
            JSONObject rules = new JSONObject(raw);
            String v = rules.optString(normalizeMerchant(merchant), "");
            return v.isEmpty() ? null : v;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static synchronized void reclassify(Context context, Tx tx, String newCategory) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(KEY, "[]"));
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !o.has("paymentMethod")) continue;
                if (o.optLong("timestamp") == tx.timestamp
                        && o.optLong("amount") == tx.amount
                        && tx.paymentMethod.equals(o.optString("paymentMethod"))
                        && tx.merchant.equals(o.optString("merchant"))) {
                    o.put("category", newCategory);
                    break;
                }
            }
            p.edit().putString(KEY, arr.toString()).apply();

            JSONObject rules = new JSONObject(p.getString(RULES, "{}"));
            rules.put(normalizeMerchant(tx.merchant), newCategory);
            p.edit().putString(RULES, rules.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static String normalizeMerchant(String merchant) {
        return merchant == null ? "" : merchant.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY)
                .remove(RULES)
                .apply();
    }
}
