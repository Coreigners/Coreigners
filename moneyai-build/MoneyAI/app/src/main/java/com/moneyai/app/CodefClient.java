package com.moneyai.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CodefClient {
    private static final String PREFS = "money_ai_codef";
    private static final String TOKEN_URL = "https://oauth.codef.io/oauth/token";
    private static final String DEV_DOMAIN = "https://development.codef.io";
    private static final String PROD_DOMAIN = "https://api.codef.io";
    private static final String KB_BANK = "0004";
    private static final String KB_CARD = "0301";
    private static final String HYUNDAI_CARD = "0302";

    public static final class Config {
        public final String clientId;
        public final String clientSecret;
        public final String connectedId;
        public final String birthDate;
        public final boolean production;

        public Config(String clientId, String clientSecret, String connectedId,
                      String birthDate, boolean production) {
            this.clientId = safe(clientId).trim();
            this.clientSecret = safe(clientSecret).trim();
            this.connectedId = safe(connectedId).trim();
            this.birthDate = safe(birthDate).replaceAll("[^0-9]", "");
            this.production = production;
        }

        public boolean complete() {
            return !clientId.isEmpty() && !clientSecret.isEmpty() && !connectedId.isEmpty();
        }
    }

    public static final class SyncResult {
        public final boolean success;
        public final String message;
        public final int transactionCount;
        public final long kbBalance;
        public final long hyundaiPending;

        SyncResult(boolean success, String message, int transactionCount,
                   long kbBalance, long hyundaiPending) {
            this.success = success;
            this.message = message;
            this.transactionCount = transactionCount;
            this.kbBalance = kbBalance;
            this.hyundaiPending = hyundaiPending;
        }
    }

    private static final class BankEntry {
        String id;
        long out;
        long in;
        long timestamp;
        String text;
    }

    private CodefClient() {}

    public static void saveConfig(Context context, Config c) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("clientId", c.clientId)
                .putString("clientSecret", c.clientSecret)
                .putString("connectedId", c.connectedId)
                .putString("birthDate", c.birthDate)
                .putBoolean("production", c.production)
                .apply();
    }

    public static Config loadConfig(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Config(
                p.getString("clientId", ""),
                p.getString("clientSecret", ""),
                p.getString("connectedId", ""),
                p.getString("birthDate", ""),
                p.getBoolean("production", false)
        );
    }

    public static void clearConfig(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static SyncResult sync(Context context, Config config) {
        if (config == null || !config.complete()) {
            return new SyncResult(false, "CODEF Client ID / Secret / Connected ID가 필요합니다.", 0, 0, 0);
        }

        try {
            String token = getToken(config.clientId, config.clientSecret);
            String domain = config.production ? PROD_DOMAIN : DEV_DOMAIN;
            String startDate = dateOffset(-370);
            String endDate = dateOffset(0);

            JSONObject bankAccountResponse = postResource(domain + "/v1/kr/bank/p/account/account-list",
                    token, jsonOf("connectedId", config.connectedId, "organization", KB_BANK));
            ensureSuccess(bankAccountResponse, "KB국민은행 계좌 조회");

            List<JSONObject> accountObjects = new ArrayList<>();
            collectObjectsWithKeys(bankAccountResponse.opt("data"), accountObjects,
                    "resAccount", "resAccountBalance");

            Map<String, Long> accounts = new LinkedHashMap<>();
            long totalBalance = 0L;
            for (JSONObject o : accountObjects) {
                String account = o.optString("resAccount", "").replaceAll("[^0-9]", "");
                if (account.isEmpty() || accounts.containsKey(account)) continue;
                String currency = o.optString("resAccountCurrency", "KRW");
                if (!currency.isEmpty() && !"KRW".equalsIgnoreCase(currency)) continue;
                long balance = money(o.optString("resAccountBalance", "0"));
                accounts.put(account, balance);
                totalBalance += Math.max(0L, balance);
            }

            if (accounts.isEmpty()) {
                throw new Exception("KB국민은행 입출금 계좌를 찾지 못했습니다. CODEF Connected ID에 KB국민은행이 연결돼 있는지 확인하세요.");
            }

            List<MoneyStore.Tx> cardTx = new ArrayList<>();
            cardTx.addAll(fetchCardApprovals(context, domain, token, config, KB_CARD,
                    "KB국민 체크카드", "CODEF-KB-CARD", startDate, endDate));
            cardTx.addAll(fetchCardApprovals(context, domain, token, config, HYUNDAI_CARD,
                    "네이버 현대카드", "CODEF-HYUNDAI-CARD", startDate, endDate));

            List<BankEntry> bankEntries = new ArrayList<>();
            for (String account : accounts.keySet()) {
                JSONObject body = new JSONObject();
                body.put("connectedId", config.connectedId);
                body.put("organization", KB_BANK);
                body.put("account", account);
                body.put("startDate", startDate);
                body.put("endDate", endDate);
                body.put("orderBy", "0");
                body.put("inquiryType", "1");
                body.put("accountPassword", "");

                JSONObject r = postResource(domain + "/v1/kr/bank/p/account/transaction-list", token, body);
                ensureSuccess(r, "KB국민은행 거래내역 조회");
                List<JSONObject> txObjects = new ArrayList<>();
                collectObjectsWithKeys(r.opt("data"), txObjects, "resAccountTrDate");
                for (JSONObject o : txObjects) {
                    BankEntry e = parseBankEntry(account, o);
                    if (e != null) bankEntries.add(e);
                }
            }

            Set<String> internalTransferIds = findInternalTransfers(bankEntries);
            long lastHyundaiPayment = 0L;
            List<MoneyStore.Tx> combined = new ArrayList<>(cardTx);
            for (BankEntry e : bankEntries) {
                String lower = e.text.toLowerCase(Locale.ROOT);
                if (e.out > 0L && contains(lower, "현대카드", "현대 카드")) {
                    lastHyundaiPayment = Math.max(lastHyundaiPayment, e.timestamp);
                    combined.add(new MoneyStore.Tx(e.id, e.out, MoneyStore.CARD_PAYMENT,
                            displayBankText(e.text), "카드대금", "KB국민은행",
                            "CODEF-KB-BANK", e.timestamp));
                    continue;
                }

                if (internalTransferIds.contains(e.id)) {
                    long amount = e.out > 0 ? e.out : e.in;
                    combined.add(new MoneyStore.Tx(e.id, amount, MoneyStore.TRANSFER,
                            displayBankText(e.text), "자금이동", "KB국민은행",
                            "CODEF-KB-BANK", e.timestamp));
                    continue;
                }

                if (e.out > 0L && matchesKbCheckApproval(e, cardTx)) {
                    combined.add(new MoneyStore.Tx(e.id, e.out, MoneyStore.TRANSFER,
                            displayBankText(e.text), "체크카드정산", "KB국민은행",
                            "CODEF-KB-BANK", e.timestamp));
                    continue;
                }

                if (e.in > 0L) {
                    String merchant = displayBankText(e.text);
                    String category = contains(lower, "급여", "월급", "salary") ? "급여" : "기타수입";
                    combined.add(new MoneyStore.Tx(e.id, e.in, MoneyStore.INCOME,
                            merchant, category, "KB국민은행", "CODEF-KB-BANK", e.timestamp));
                } else if (e.out > 0L) {
                    String merchant = displayBankText(e.text);
                    String category = MoneyStore.categoryFor(context, merchant, e.text);
                    combined.add(new MoneyStore.Tx(e.id, e.out, MoneyStore.EXPENSE,
                            merchant, category, "KB국민은행", "CODEF-KB-BANK", e.timestamp));
                }
            }

            long pending = estimateHyundaiPending(cardTx, lastHyundaiPayment);
            MoneyStore.replaceApiTransactions(context, combined);
            MoneyStore.setBalance(context, totalBalance, pending, true);
            saveConfig(context, config);

            return new SyncResult(true,
                    "동기화 완료 · KB 잔액과 최근 12개월 거래를 갱신했습니다.",
                    combined.size(), totalBalance, pending);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
            return new SyncResult(false, msg, 0, 0, 0);
        }
    }

    private static List<MoneyStore.Tx> fetchCardApprovals(Context context, String domain, String token,
                                                          Config config, String organization,
                                                          String paymentMethod, String source,
                                                          String startDate, String endDate) throws Exception {
        JSONObject body = new JSONObject();
        body.put("connectedId", config.connectedId);
        body.put("organization", organization);
        body.put("birthDate", config.birthDate);
        body.put("startDate", startDate);
        body.put("endDate", endDate);
        body.put("orderBy", "0");
        body.put("inquiryType", "1");
        body.put("cardNo", "");
        body.put("cardName", "");
        body.put("duplicateCardSelect", "");
        body.put("duplicateCardIdx", "");
        body.put("memberStoreInfoType", "0");

        JSONObject r = postResource(domain + "/v1/kr/card/p/account/approval-list", token, body);
        ensureSuccess(r, paymentMethod + " 승인내역 조회");
        List<JSONObject> objects = new ArrayList<>();
        collectObjectsWithKeys(r.opt("data"), objects, "resUsedDate", "resUsedAmount");

        List<MoneyStore.Tx> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JSONObject o : objects) {
            String date = o.optString("resUsedDate", "");
            if (date.length() != 8) continue;
            String time = o.optString("resUsedTime", "000000");
            long ts = parseTimestamp(date, time);
            long amount = money(o.optString("resUsedAmount", "0"));
            long cancelAmount = money(o.optString("resCancelAmount", "0"));
            boolean canceled = "1".equals(o.optString("resCancelYN")) || cancelAmount > 0L;
            if (amount <= 0L && cancelAmount <= 0L) continue;

            String merchant = o.optString("resMemberStoreName", "").trim();
            if (merchant.isEmpty()) merchant = o.optString("resCardName", "카드 사용").trim();
            String storeType = o.optString("resMemberStoreType", "");
            String approval = o.optString("resApprovalNo", "");
            String cardNo = o.optString("resCardNo", "");
            String id = source + "|" + date + "|" + time + "|" + approval + "|" + amount + "|" + cardNo;
            if (!ids.add(id)) continue;
            String category = MoneyStore.categoryFor(context, merchant, storeType);
            out.add(new MoneyStore.Tx(id,
                    canceled ? (cancelAmount > 0L ? cancelAmount : amount) : amount,
                    canceled ? MoneyStore.REFUND : MoneyStore.EXPENSE,
                    merchant,
                    category,
                    paymentMethod,
                    source,
                    ts));
        }
        return out;
    }

    private static BankEntry parseBankEntry(String account, JSONObject o) {
        String date = o.optString("resAccountTrDate", "");
        if (date.length() != 8) return null;
        BankEntry e = new BankEntry();
        String time = o.optString("resAccountTrTime", "000000");
        e.timestamp = parseTimestamp(date, time);
        e.out = money(o.optString("resAccountOut", "0"));
        e.in = money(o.optString("resAccountIn", "0"));
        if (e.out <= 0L && e.in <= 0L) return null;
        StringBuilder s = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            String v = o.optString("resAccountDesc" + i, "").trim();
            if (!v.isEmpty()) {
                if (s.length() > 0) s.append(" / ");
                s.append(v);
            }
        }
        e.text = s.length() == 0 ? "계좌 거래" : s.toString();
        e.id = "CODEF-KB-BANK|" + account + "|" + date + "|" + time + "|" + e.out + "|" + e.in + "|" + e.text;
        return e;
    }

    private static Set<String> findInternalTransfers(List<BankEntry> entries) {
        Set<String> marked = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            BankEntry a = entries.get(i);
            if (a.out <= 0L) continue;
            for (int j = 0; j < entries.size(); j++) {
                BankEntry b = entries.get(j);
                if (b.in <= 0L || a.out != b.in) continue;
                if (Math.abs(a.timestamp - b.timestamp) <= 24L * 60L * 60L * 1000L) {
                    marked.add(a.id);
                    marked.add(b.id);
                    break;
                }
            }
        }
        return marked;
    }

    private static boolean matchesKbCheckApproval(BankEntry entry, List<MoneyStore.Tx> cardTx) {
        if (entry.out <= 0L) return false;
        for (MoneyStore.Tx tx : cardTx) {
            if (!"CODEF-KB-CARD".equals(tx.source)) continue;
            if (!MoneyStore.EXPENSE.equals(tx.direction)) continue;
            if (tx.amount != entry.out) continue;
            if (Math.abs(tx.timestamp - entry.timestamp) <= 36L * 60L * 60L * 1000L) return true;
        }
        return false;
    }

    private static long estimateHyundaiPending(List<MoneyStore.Tx> cardTx, long lastPayment) {
        long cutoff = lastPayment;
        if (cutoff <= 0L) cutoff = System.currentTimeMillis() - 45L * 24L * 60L * 60L * 1000L;
        long pending = 0L;
        for (MoneyStore.Tx tx : cardTx) {
            if (!"CODEF-HYUNDAI-CARD".equals(tx.source) || tx.timestamp <= cutoff) continue;
            if (MoneyStore.EXPENSE.equals(tx.direction)) pending += tx.amount;
            else if (MoneyStore.REFUND.equals(tx.direction)) pending -= tx.amount;
        }
        return Math.max(0L, pending);
    }

    private static String getToken(String clientId, String secret) throws Exception {
        URL url = new URL(TOKEN_URL);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        String basic = Base64.encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        c.setRequestProperty("Authorization", "Basic " + basic);
        byte[] body = "grant_type=client_credentials&scope=read".getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        String raw = readResponse(c);
        JSONObject json = parseResponse(raw);
        String token = json.optString("access_token", "");
        if (token.isEmpty()) {
            throw new Exception("CODEF 토큰 발급 실패: " + compactError(json));
        }
        return token;
    }

    private static JSONObject postResource(String url, String token, JSONObject jsonBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(45000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + token);
        String encoded = URLEncoder.encode(jsonBody.toString(), "UTF-8");
        try (OutputStream os = c.getOutputStream()) {
            os.write(encoded.getBytes(StandardCharsets.UTF_8));
        }
        return parseResponse(readResponse(c));
    }

    private static String readResponse(HttpURLConnection c) throws Exception {
        int status = c.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
        if (stream == null) throw new Exception("HTTP " + status + " 응답 본문이 없습니다.");
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static JSONObject parseResponse(String raw) throws Exception {
        String text = safe(raw).trim();
        if (!text.startsWith("{")) text = URLDecoder.decode(text, "UTF-8");
        if (!text.startsWith("{")) throw new Exception("CODEF 응답 형식을 해석하지 못했습니다.");
        return new JSONObject(text);
    }

    private static void ensureSuccess(JSONObject json, String label) throws Exception {
        if (json.has("error")) throw new Exception(label + " 실패: " + compactError(json));
        JSONObject result = json.optJSONObject("result");
        if (result == null) return;
        String code = result.optString("code", "");
        if (!code.isEmpty() && !"CF-00000".equals(code)) {
            throw new Exception(label + " 실패: " + code + " " + result.optString("message", ""));
        }
    }

    private static String compactError(JSONObject json) {
        JSONObject result = json.optJSONObject("result");
        if (result != null) {
            return result.optString("code", "") + " " + result.optString("message", "");
        }
        return json.optString("error_description", json.optString("error", "알 수 없는 오류"));
    }

    private static void collectObjectsWithKeys(Object node, List<JSONObject> out, String... keys) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            boolean ok = true;
            for (String key : keys) if (!o.has(key)) { ok = false; break; }
            if (ok) out.add(o);
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i);
                    collectObjectsWithKeys(o.opt(key), out, keys);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectObjectsWithKeys(a.opt(i), out, keys);
        }
    }

    private static JSONObject jsonOf(String k1, String v1, String k2, String v2) throws Exception {
        JSONObject o = new JSONObject();
        o.put(k1, v1);
        o.put(k2, v2);
        return o;
    }

    private static long parseTimestamp(String date, String time) {
        try {
            String t = safe(time).replaceAll("[^0-9]", "");
            while (t.length() < 6) t += "0";
            return new SimpleDateFormat("yyyyMMddHHmmss", Locale.KOREA).parse(date + t.substring(0, 6)).getTime();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }

    private static String dateOffset(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, days);
        return new SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(c.getTime());
    }

    private static long money(String value) {
        try {
            String s = safe(value).replaceAll("[^0-9-]", "");
            if (s.isEmpty() || "-".equals(s)) return 0L;
            return Math.abs(Long.parseLong(s));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String displayBankText(String raw) {
        String s = safe(raw).replaceAll("\\s*/\\s*", " · ").trim();
        return s.isEmpty() ? "계좌 거래" : s;
    }

    private static boolean contains(String s, String... words) {
        for (String w : words) if (s.contains(w.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
