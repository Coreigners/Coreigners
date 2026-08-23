package com.moneyai.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MoneyStore {
    private static final String PREFS = "money_ai";
    private static final String KEY = "transactions_v4";
    private static final String RULES = "merchant_category_rules";
    private static final String BALANCE = "balance_v4";
    private static final String LAST_SYNC = "last_sync";

    public static final String EXPENSE = "EXPENSE";
    public static final String INCOME = "INCOME";
    public static final String REFUND = "REFUND";
    public static final String TRANSFER = "TRANSFER";
    public static final String CARD_PAYMENT = "CARD_PAYMENT";

    public static final class Tx {
        public final String id;
        public final long amount;
        public final String direction;
        public final String merchant;
        public final String category;
        public final String paymentMethod;
        public final String source;
        public final long timestamp;

        public Tx(String id, long amount, String direction, String merchant, String category,
                  String paymentMethod, String source, long timestamp) {
            this.id = id == null || id.isEmpty()
                    ? source + "|" + timestamp + "|" + amount + "|" + merchant
                    : id;
            this.amount = Math.max(amount, 0L);
            this.direction = safe(direction);
            this.merchant = safe(merchant);
            this.category = safe(category);
            this.paymentMethod = safe(paymentMethod);
            this.source = safe(source);
            this.timestamp = timestamp;
        }

        // v0.3 listener source compatibility. The notification listener is no longer registered.
        public Tx(long amount, String direction, String merchant, String category,
                  String paymentMethod, String source, long timestamp) {
            this(source + "|" + timestamp + "|" + amount + "|" + merchant,
                    amount, direction, merchant, category, paymentMethod, source, timestamp);
        }
    }

    public static final class Balance {
        public final long kbBalance;
        public final long hyundaiPending;
        public final boolean pendingEstimated;
        public final long updatedAt;

        Balance(long kbBalance, long hyundaiPending, boolean pendingEstimated, long updatedAt) {
            this.kbBalance = Math.max(0L, kbBalance);
            this.hyundaiPending = Math.max(0L, hyundaiPending);
            this.pendingEstimated = pendingEstimated;
            this.updatedAt = updatedAt;
        }

        public long availableMoney() {
            return Math.max(0L, kbBalance - hyundaiPending);
        }
    }

    public static final class MonthSummary {
        public final int year;
        public final int month;
        public final long income;
        public final long expense;
        public final long saved;

        MonthSummary(int year, int month, long income, long expense) {
            this.year = year;
            this.month = month;
            this.income = income;
            this.expense = expense;
            this.saved = income - expense;
        }

        public String key() {
            return String.format(Locale.KOREA, "%04d-%02d", year, month);
        }
    }

    private MoneyStore() {}

    public static synchronized void replaceApiTransactions(Context context, List<Tx> incoming) {
        try {
            SharedPreferences p = prefs(context);
            JSONObject rules = new JSONObject(p.getString(RULES, "{}"));
            JSONArray arr = new JSONArray();
            Map<String, Tx> unique = new LinkedHashMap<>();
            for (Tx tx : incoming) unique.put(tx.id, tx);
            for (Tx tx : unique.values()) {
                String learned = rules.optString(normalizeMerchant(tx.merchant), "");
                Tx saved = learned.isEmpty() ? tx : new Tx(
                        tx.id, tx.amount, tx.direction, tx.merchant, learned,
                        tx.paymentMethod, tx.source, tx.timestamp);
                arr.put(toJson(saved));
            }
            p.edit().putString(KEY, arr.toString()).putLong(LAST_SYNC, System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized void add(Context context, Tx tx) {
        try {
            SharedPreferences p = prefs(context);
            JSONArray arr = new JSONArray(p.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject old = arr.optJSONObject(i);
                if (old != null && tx.id.equals(old.optString("id"))) return;
            }
            String learned = learnedCategory(context, tx.merchant);
            Tx saved = learned == null ? tx : new Tx(
                    tx.id, tx.amount, tx.direction, tx.merchant, learned,
                    tx.paymentMethod, tx.source, tx.timestamp);
            arr.put(toJson(saved));
            p.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized List<Tx> all(Context context) {
        List<Tx> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(context).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new Tx(
                        o.optString("id"),
                        o.optLong("amount"),
                        o.optString("direction"),
                        o.optString("merchant"),
                        o.optString("category", "기타"),
                        o.optString("paymentMethod"),
                        o.optString("source"),
                        o.optLong("timestamp")
                ));
            }
        } catch (Exception ignored) {}
        out.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return out;
    }

    public static synchronized void setBalance(Context context, long kbBalance, long hyundaiPending,
                                               boolean pendingEstimated) {
        try {
            JSONObject o = new JSONObject();
            o.put("kbBalance", Math.max(0L, kbBalance));
            o.put("hyundaiPending", Math.max(0L, hyundaiPending));
            o.put("pendingEstimated", pendingEstimated);
            o.put("updatedAt", System.currentTimeMillis());
            prefs(context).edit().putString(BALANCE, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized Balance balance(Context context) {
        try {
            JSONObject o = new JSONObject(prefs(context).getString(BALANCE, "{}"));
            return new Balance(
                    o.optLong("kbBalance", 0L),
                    o.optLong("hyundaiPending", 0L),
                    o.optBoolean("pendingEstimated", true),
                    o.optLong("updatedAt", 0L));
        } catch (Exception ignored) {
            return new Balance(0L, 0L, true, 0L);
        }
    }

    public static long lastSync(Context context) {
        return prefs(context).getLong(LAST_SYNC, 0L);
    }

    public static MonthSummary monthSummary(Context context, int year, int month) {
        long[] range = monthRange(year, month);
        long income = 0L;
        long expense = 0L;
        for (Tx tx : all(context)) {
            if (tx.timestamp < range[0] || tx.timestamp >= range[1]) continue;
            if (INCOME.equals(tx.direction)) income += tx.amount;
            else if (EXPENSE.equals(tx.direction)) expense += tx.amount;
            else if (REFUND.equals(tx.direction)) expense -= tx.amount;
        }
        return new MonthSummary(year, month, Math.max(0L, income), Math.max(0L, expense));
    }

    public static List<MonthSummary> recentMonths(Context context, int count) {
        List<MonthSummary> out = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        for (int i = 0; i < count; i++) {
            out.add(monthSummary(context, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1));
            c.add(Calendar.MONTH, -1);
        }
        return out;
    }

    public static Map<String, Long> categoryTotals(Context context, int year, int month) {
        long[] range = monthRange(year, month);
        Map<String, Long> map = new HashMap<>();
        for (Tx tx : all(context)) {
            if (tx.timestamp < range[0] || tx.timestamp >= range[1]) continue;
            if (EXPENSE.equals(tx.direction)) {
                map.put(tx.category, map.getOrDefault(tx.category, 0L) + tx.amount);
            } else if (REFUND.equals(tx.direction)) {
                map.put(tx.category, map.getOrDefault(tx.category, 0L) - tx.amount);
            }
        }
        List<Map.Entry<String, Long>> rows = new ArrayList<>(map.entrySet());
        rows.removeIf(e -> e.getValue() <= 0L);
        rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        Map<String, Long> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : rows) sorted.put(e.getKey(), e.getValue());
        return sorted;
    }

    public static synchronized String learnedCategory(Context context, String merchant) {
        try {
            JSONObject rules = new JSONObject(prefs(context).getString(RULES, "{}"));
            String v = rules.optString(normalizeMerchant(merchant), "");
            return v.isEmpty() ? null : v;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static synchronized void reclassify(Context context, Tx tx, String newCategory) {
        try {
            SharedPreferences p = prefs(context);
            JSONArray arr = new JSONArray(p.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && tx.id.equals(o.optString("id"))) {
                    o.put("category", newCategory);
                    break;
                }
            }
            JSONObject rules = new JSONObject(p.getString(RULES, "{}"));
            rules.put(normalizeMerchant(tx.merchant), newCategory);
            p.edit().putString(KEY, arr.toString()).putString(RULES, rules.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static String categoryFor(Context context, String merchant, String storeType) {
        String learned = learnedCategory(context, merchant);
        if (learned != null) return learned;
        String s = (safe(merchant) + " " + safe(storeType)).toLowerCase(Locale.ROOT);
        if (contains(s, "스타벅스", "투썸", "메가커피", "컴포즈", "커피", "카페", "coffee")) return "카페";
        if (contains(s, "cu", "gs25", "세븐일레븐", "이마트24", "편의점")) return "편의점";
        if (contains(s, "배달", "배민", "쿠팡이츠", "요기요", "식당", "음식", "치킨", "피자", "버거", "외식")) return "식비";
        if (contains(s, "무신사", "29cm", "올리브영", "쇼핑", "백화점", "의류", "쿠팡", "네이버")) return "쇼핑/의류";
        if (contains(s, "택시", "카카오t", "우버", "버스", "지하철", "코레일", "srt", "교통")) return "교통";
        if (contains(s, "주유", "오일뱅크", "sk에너지", "gs칼텍스", "s-oil", "쏘카", "주차")) return "주유/차량";
        if (contains(s, "넷플릭스", "youtube", "유튜브", "spotify", "구독", "멤버십")) return "구독";
        if (contains(s, "병원", "의원", "약국", "치과", "의료")) return "의료";
        if (contains(s, "cgv", "롯데시네마", "메가박스", "테니스", "헬스", "골프", "영화", "공연", "문화")) return "취미/문화";
        if (contains(s, "호텔", "항공", "대한항공", "아시아나", "에어", "여행", "숙박")) return "여행";
        if (contains(s, "skt", "kt", "lg유플러스", "통신")) return "통신";
        if (contains(s, "전기", "가스", "수도", "관리비", "공과금")) return "공과금";
        if (contains(s, "보험")) return "보험";
        if (contains(s, "학원", "교육", "교보문고", "yes24", "알라딘")) return "교육/도서";
        return "기타";
    }

    public static synchronized void seedDemo(Context context) {
        List<Tx> demo = new ArrayList<>();
        Calendar base = Calendar.getInstance();
        base.set(Calendar.DAY_OF_MONTH, 10);
        base.set(Calendar.HOUR_OF_DAY, 12);
        base.set(Calendar.MINUTE, 0);
        base.set(Calendar.SECOND, 0);
        base.set(Calendar.MILLISECOND, 0);

        String[] merchants = {"스타벅스", "김밥천국", "무신사", "CGV", "카카오T", "CU", "테니스코트"};
        String[] cats = {"카페", "식비", "쇼핑/의류", "취미/문화", "교통", "편의점", "취미/문화"};
        long[] amounts = {5800, 9000, 62900, 15000, 18200, 7300, 45000};

        for (int m = 3; m >= 0; m--) {
            Calendar c = (Calendar) base.clone();
            c.add(Calendar.MONTH, -m);
            long salary = m == 0 ? 3250000L : (m == 1 ? 3100000L : 3150000L);
            demo.add(new Tx("demo-salary-" + m, salary, INCOME, "급여", "급여",
                    "KB국민은행", "DEMO", c.getTimeInMillis()));
            for (int i = 0; i < merchants.length; i++) {
                Calendar d = (Calendar) c.clone();
                d.add(Calendar.DAY_OF_MONTH, i * 2 + 1);
                long multiplier = 1L + ((m + i) % 3);
                demo.add(new Tx("demo-" + m + "-" + i, amounts[i] * multiplier, EXPENSE,
                        merchants[i], cats[i], i % 2 == 0 ? "네이버 현대카드" : "KB국민 체크카드",
                        "DEMO", d.getTimeInMillis()));
            }
            Calendar rent = (Calendar) c.clone();
            rent.add(Calendar.DAY_OF_MONTH, 3);
            demo.add(new Tx("demo-rent-" + m, 550000L, EXPENSE, "주거비", "생활",
                    "KB국민은행", "DEMO", rent.getTimeInMillis()));
        }
        replaceApiTransactions(context, demo);
        setBalance(context, 4032000L, 550000L, true);
    }

    public static synchronized void clear(Context context) {
        prefs(context).edit()
                .remove(KEY)
                .remove(RULES)
                .remove(BALANCE)
                .remove(LAST_SYNC)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject toJson(Tx tx) throws Exception {
        JSONObject o = new JSONObject();
        o.put("schema", 4);
        o.put("id", tx.id);
        o.put("amount", tx.amount);
        o.put("direction", tx.direction);
        o.put("merchant", tx.merchant);
        o.put("category", tx.category);
        o.put("paymentMethod", tx.paymentMethod);
        o.put("source", tx.source);
        o.put("timestamp", tx.timestamp);
        return o;
    }

    private static long[] monthRange(int year, int month) {
        Calendar start = Calendar.getInstance();
        start.clear();
        start.set(year, month - 1, 1, 0, 0, 0);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.MONTH, 1);
        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
    }

    private static String normalizeMerchant(String merchant) {
        return safe(merchant).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean contains(String s, String... words) {
        for (String word : words) if (s.contains(word.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
