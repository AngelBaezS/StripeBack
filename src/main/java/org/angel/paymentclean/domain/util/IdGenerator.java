package org.angel.paymentclean.domain.util;

import java.security.SecureRandom;

public final class IdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int ID_LENGTH = 16;

    private IdGenerator() {}

    private static String generatePrefixedId(String prefix) {
        StringBuilder sb = new StringBuilder(prefix.length() + ID_LENGTH);
        sb.append(prefix);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String userId() {
        return generatePrefixedId("usr_");
    }

    public static String productId() {
        return generatePrefixedId("prd_");
    }

    public static String cartId() {
        return generatePrefixedId("crt_");
    }

    public static String cartItemId() {
        return generatePrefixedId("cit_");
    }

    public static String orderId() {
        return generatePrefixedId("ord_");
    }

    public static String orderItemId() {
        return generatePrefixedId("oit_");
    }
}

