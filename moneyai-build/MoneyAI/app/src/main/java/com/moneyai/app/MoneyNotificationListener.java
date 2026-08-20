package com.moneyai.app;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MoneyNotificationListener extends NotificationListenerService {
    private static final String TOSS = "viva.republica.toss";
    private static final Pattern AMOUNT = Pattern.compile("(?<!\\d)([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\s*원");

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !TOSS.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        Bundle e = n.extras;
        String title = s(e.getCharSequence(Notification.EXTRA_TITLE));
        String text = s(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) text = s(e.getCharSequence(Notification.EXTRA_TEXT));
        String raw = (title + "\n" + text).trim();
        Matcher m = AMOUNT.matcher(raw);
        if (!m.find()) return;
        long amount;
        try { amount = Long.parseLong(m.group(1).replace(",", "")); } catch (Exception ex) { return; }
        String direction = contains(raw, "입금", "받았", "환불", "취소") ? "INCOME" : "EXPENSE";
        String merchant = title.isEmpty() || "토스".equals(title) ? guessMerchant(text) : title;
        String category = category(raw + " " + merchant);
        MoneyStore.add(getApplicationContext(), new MoneyStore.Tx(amount, direction, merchant, category, sbn.getPostTime()));
    }

    private static String s(CharSequence c) { return c == null ? "" : c.toString(); }
    private static boolean contains(String s, String... words) { for (String w : words) if (s.contains(w)) return true; return false; }
    private static String guessMerchant(String text) {
        String cleaned = text.replaceAll("[0-9]{1,3}(?:,[0-9]{3})*\\s*원", " ")
                .replaceAll("결제|승인|사용|출금|입금|송금|이체|보냈어요?|받았어요?|충전|취소|환불|잔액", " ")
                .replaceAll("\\s+", " ").trim();
        return cleaned.length() >= 2 && cleaned.length() <= 40 ? cleaned : "토스 거래";
    }
    private static String category(String input) {
        String s = input.toLowerCase(Locale.ROOT);
        if (contains(s,"스타벅스","투썸","메가커피","컴포즈","커피","카페","coffee")) return "카페";
        if (contains(s,"배달","배민","쿠팡이츠","요기요","식당","치킨","피자","버거")) return "식비";
        if (contains(s,"쿠팡","무신사","29cm","올리브영","쇼핑","네이버페이")) return "쇼핑";
        if (contains(s,"택시","카카오t","우버","버스","지하철","코레일","srt")) return "교통";
        if (contains(s,"넷플릭스","youtube","유튜브","spotify","스포티파이","구독")) return "구독";
        if (contains(s,"송금","이체","입금","출금")) return "이체";
        return "기타";
    }
}
