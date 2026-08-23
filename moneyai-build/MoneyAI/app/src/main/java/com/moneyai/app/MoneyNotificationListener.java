package com.moneyai.app;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MoneyNotificationListener extends NotificationListenerService {
    private static final String KB_PAY = "com.kbcard.cxh.appcard";
    private static final String HYUNDAI_CARD = "com.hyundaicard.appcard";
    private static final String KB_BANK = "com.kbstar.kbbank";

    private static final Pattern AMOUNT = Pattern.compile("(?<!\\d)([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\s*원");

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (!KB_PAY.equals(pkg) && !HYUNDAI_CARD.equals(pkg) && !KB_BANK.equals(pkg)) return;

        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;

        Bundle e = n.extras;
        String title = s(e.getCharSequence(Notification.EXTRA_TITLE));
        String big = s(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String text = big.isEmpty() ? s(e.getCharSequence(Notification.EXTRA_TEXT)) : big;
        String sub = s(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String info = s(e.getCharSequence(Notification.EXTRA_INFO_TEXT));
        String raw = (title + "\n" + text + "\n" + sub + "\n" + info).trim();
        if (raw.isEmpty()) return;

        // Never record investment/marketing/news notifications even if they contain a won amount.
        if (contains(raw, "주식", "증권", "코스피", "코스닥", "시세", "수익률", "종목", "투자정보")) return;
        if (contains(raw, "이벤트", "혜택", "쿠폰", "광고", "추천") && !isTransactionText(raw)) return;

        String paymentMethod;
        String source;
        String direction;

        if (KB_PAY.equals(pkg)) {
            if (!contains(raw, "승인", "결제", "사용", "취소", "환불")) return;
            paymentMethod = "KB국민 체크카드";
            source = "KB Pay";
            direction = contains(raw, "취소", "환불") ? "REFUND" : "EXPENSE";
        } else if (HYUNDAI_CARD.equals(pkg)) {
            if (!contains(raw, "승인", "결제", "사용", "취소", "환불", "일시불", "할부")) return;
            paymentMethod = "네이버 현대카드";
            source = "현대카드";
            direction = contains(raw, "취소", "환불") ? "REFUND" : "EXPENSE";
        } else {
            // KB Star Banking is used only for account income / transfers.
            // Ignore likely check-card withdrawals to prevent double counting with KB Pay.
            if (!contains(raw, "입금", "출금", "이체", "송금", "받았", "보냈")) return;
            if (contains(raw, "체크카드", "카드승인", "KB카드", "국민카드", "신용카드")) return;
            paymentMethod = "KB국민은행 계좌";
            source = "KB스타뱅킹";
            direction = contains(raw, "입금", "받았") ? "INCOME" : "EXPENSE";
        }

        Matcher m = AMOUNT.matcher(raw);
        if (!m.find()) return;

        long amount;
        try {
            amount = Long.parseLong(m.group(1).replace(",", ""));
        } catch (Exception ex) {
            return;
        }
        if (amount <= 0L) return;

        String merchant = guessMerchant(raw, title, source);
        Context context = getApplicationContext();
        String learned = MoneyStore.learnedCategory(context, merchant);
        String category = learned != null ? learned : category(raw + " " + merchant, direction, source);

        MoneyStore.add(context, new MoneyStore.Tx(
                amount,
                direction,
                merchant,
                category,
                paymentMethod,
                source,
                sbn.getPostTime()
        ));
    }

    private static String s(CharSequence c) {
        return c == null ? "" : c.toString();
    }

    private static boolean contains(String s, String... words) {
        if (s == null) return false;
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static boolean isTransactionText(String raw) {
        return contains(raw, "승인", "결제", "사용", "취소", "환불", "입금", "출금", "이체", "송금");
    }

    private static String guessMerchant(String raw, String title, String source) {
        String cleaned = raw
                .replace("KB Pay", " ")
                .replace("KB페이", " ")
                .replace("KB국민카드", " ")
                .replace("국민카드", " ")
                .replace("현대카드", " ")
                .replace("KB스타뱅킹", " ")
                .replace("국민은행", " ")
                .replaceAll("[0-9]{1,3}(?:,[0-9]{3})*\\s*원", " ")
                .replaceAll("승인|결제|사용|출금|입금|송금|이체|취소|환불|일시불|할부|체크카드|신용카드|잔액|누적|이용|완료|되었습니다|됐어요|되었어요", " ")
                .replaceAll("\\d{2,4}[-./]\\d{1,2}[-./]\\d{1,2}", " ")
                .replaceAll("\\d{1,2}:\\d{2}", " ")
                .replaceAll("[*xX•●]{2,}\\d{2,4}", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (!cleaned.isEmpty() && cleaned.length() <= 48) return cleaned;

        if (title != null && !title.isEmpty()
                && !title.equals(source)
                && !contains(title, "KB Pay", "현대카드", "KB스타뱅킹", "국민은행")) {
            return title.trim();
        }
        return source + " 거래";
    }

    private static String category(String input, String direction, String source) {
        String s = input.toLowerCase(Locale.ROOT);

        if ("INCOME".equals(direction)) {
            if (contains(s, "급여", "월급", "급료", "상여")) return "급여";
            if (contains(s, "환급", "캐시백", "이자", "배당")) return "기타수입";
            return "입금";
        }
        if ("REFUND".equals(direction)) return "환불/취소";

        if (contains(s, "스타벅스", "투썸", "메가커피", "컴포즈", "빽다방", "이디야", "폴바셋", "커피", "카페", "coffee")) return "카페";
        if (contains(s, "cu ", "씨유", "gs25", "세븐일레븐", "이마트24", "미니스톱", "편의점")) return "편의점";
        if (contains(s, "배달", "배민", "배달의민족", "쿠팡이츠", "요기요", "식당", "음식", "치킨", "피자", "버거", "맥도날드", "롯데리아", "맘스터치", "김밥", "고기", "국밥", "회 ", "초밥", "restaurant")) return "식비";
        if (contains(s, "쿠팡", "무신사", "29cm", "에이블리", "지그재그", "올리브영", "백화점", "아울렛", "쇼핑", "스마트스토어", "네이버페이", "naver pay")) return "쇼핑";
        if (contains(s, "택시", "카카오t", "카카오 t", "우버", "버스", "지하철", "코레일", "srt", "티머니", "주차", "하이패스")) return "교통";
        if (contains(s, "sk에너지", "gs칼텍스", "s-oil", "에쓰오일", "현대오일", "주유", "충전소")) return "주유/차량";
        if (contains(s, "넷플릭스", "netflix", "youtube", "유튜브", "spotify", "스포티파이", "왓챠", "티빙", "디즈니", "쿠팡플레이", "구독", "멤버십")) return "구독";
        if (contains(s, "병원", "의원", "약국", "치과", "한의원", "건강", "clinic", "pharmacy")) return "의료";
        if (contains(s, "영화", "cgv", "메가박스", "롯데시네마", "공연", "전시", "티켓", "게임", "steam", "스팀")) return "문화/여가";
        if (contains(s, "호텔", "숙박", "에어비앤비", "airbnb", "야놀자", "여기어때", "항공", "대한항공", "아시아나", "제주항공", "진에어", "여행")) return "여행";
        if (contains(s, "통신", "skt", "kt ", "lgu", "lg u", "휴대폰", "인터넷요금")) return "통신";
        if (contains(s, "전기", "가스", "수도", "관리비", "아파트", "공과금")) return "생활/공과금";
        if (contains(s, "보험", "생명", "화재")) return "보험";
        if (contains(s, "학원", "교육", "교재", "서점", "교보문고", "yes24", "알라딘")) return "교육";
        if (contains(s, "세금", "국세", "지방세", "정부24", "위택스", "홈택스")) return "세금";
        if (contains(s, "송금", "이체") || "KB스타뱅킹".equals(source)) return "이체/현금";
        return "기타";
    }
}
