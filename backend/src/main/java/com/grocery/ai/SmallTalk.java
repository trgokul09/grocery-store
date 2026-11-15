package com.grocery.ai;

import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Handles everyday conversation (greetings, thanks, "how are you", "what can you do") naturally,
 * without touching the database, so the assistant doesn't treat every message as a query.
 */
public final class SmallTalk {

    public record Reply(String text, List<String> suggestions) {}

    private static final Pattern BUSINESS = Pattern.compile(
        "\\b(sale|sales|sell|sold|selling|revenue|profit|loss|margin|stock|restock|inventory|product|products|expense|expenses|spend|spent|" +
        "customer|customers|owe|owes|owed|pending|due|salary|salaries|payroll|attendance|absent|present|employee|employees|staff|supplier|suppliers|" +
        "cash|upi|balance|order|orders|purchase|expir\\w*|category|categories|bill|bills|invoice|shift|leave|report|suggest\\w*|recommend\\w*|advice|" +
        "improve|business|earn\\w*|income|turnover|money|today|yesterday|month|week)\\b");

    private static final Pattern GREETING = Pattern.compile("^(hi+|hii+|hello+|hey+|hey there|hi there|hello there|yo|hola|namaste|namaskar|vanakkam|good (morning|afternoon|evening|day)|gm|morning|evening)( (there|gpt|grocerygpt|buddy|friend|bot))?$");
    private static final Pattern GREETING_PREFIX = Pattern.compile("^(hi+|hello+|hey+|namaste|good (morning|afternoon|evening))[ ,!.]+");
    private static final Pattern HOW_ARE_YOU = Pattern.compile("^(hi |hello |hey )?(how are you( doing)?( today)?|how r u|how are u|how do you do|how('s| is) it going|how have you been|what'?s up|wassup|sup)$");
    private static final Pattern CAPABILITIES = Pattern.compile("^(what can you do( for me)?|what do you do|what are you capable of|how can you help( me)?|what can i ask( you)?|help|help me|what are your features|what all can you do|menu|options)$");
    private static final Pattern WHO = Pattern.compile("^(who are you|what are you|what is your name|what'?s your name|are you a bot|are you human|introduce yourself)$");
    private static final Pattern THANKS = Pattern.compile("^(thanks?( you)?( so much| a lot| very much)?( gpt| buddy)?|thx|ty|thank u|thanks a ton|much appreciated|appreciate it|great thanks|ok thanks|okay thanks|thanks bro|dhanyavad|shukriya)$");
    private static final Pattern BYE = Pattern.compile("^(bye+|goodbye|good bye|see you( later)?|see ya|good night|gn|talk (to you )?later|ttyl|that'?s all|nothing else|no thanks|no thank you)$");
    private static final Pattern ACK = Pattern.compile("^(ok+|okay|k|cool|great|nice|awesome|perfect|got it|alright|all right|sure|fine|hmm+|i see|understood|wow|good|super)$");
    private static final Pattern MOOD_GOOD = Pattern.compile("^(it'?s |its |business is |all )?(good|great|going good|going well|doing well|doing good|pretty good|not bad|fantastic|excellent|busy|very good)$");
    private static final Pattern MOOD_BAD = Pattern.compile("^(it'?s |its |business is )?(not good|bad|slow|very slow|dull|not great|terrible|so so|so-so|could be better|not so good|down)$");

    private SmallTalk() {}

    public static String normalize(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT)
            .replaceAll("[\\u2019']", "'")
            .replaceAll("[^a-z0-9' ]", " ")
            .replaceAll("\\s+", " ").trim();
    }

    public static boolean mentionsBusiness(String normalized) { return BUSINESS.matcher(normalized).find(); }

    /** If the message starts with a greeting followed by a real question, returns a short friendly prefix. */
    public static String greetingPrefix(String raw, String firstName) {
        String n = normalize(raw);
        if (GREETING_PREFIX.matcher(n + " ").find() && n.split(" ").length > 2) {
            return "Hi " + firstName + "! ";
        }
        return "";
    }

    public static Reply reply(String raw, String firstName, String role) {
        String q = normalize(raw);
        if (q.isEmpty()) return null;
        boolean mgmt = !"STAFF".equals(role);

        if (GREETING.matcher(q).matches()) {
            String hello = q.startsWith("good morning") || q.equals("gm") || q.equals("morning") ? "Good morning"
                : q.startsWith("good afternoon") ? "Good afternoon"
                : q.startsWith("good evening") || q.equals("evening") ? "Good evening"
                : q.startsWith("namaste") || q.startsWith("namaskar") ? "Namaste"
                : timeGreeting();
            return new Reply(pick(
                    hello + ", " + firstName + "! Welcome back. How's your business going today?",
                    "Hey " + firstName + "! Good to see you. How's business today?",
                    hello + ", " + firstName + "! What would you like to check today?"),
                starter(mgmt));
        }
        if (HOW_ARE_YOU.matcher(q).matches()) {
            return new Reply(pick(
                    "I'm doing great, thanks for asking! Ready to help you manage your business. How are things at the store?",
                    "All good here, thanks! I've got your store's numbers ready whenever you need them. How's your day going?"),
                starter(mgmt));
        }
        if (CAPABILITIES.matcher(q).matches()) {
            String text = mgmt
                ? "I'm your store's business assistant. I can:\n\n" +
                  "- **Track sales** for today, yesterday, this week or any month, and explain *why* they went up or down\n" +
                  "- **Break down your profit** after the cost of goods and expenses\n" +
                  "- **Watch your inventory** for low stock, expiring items and products that aren't selling\n" +
                  "- **Spot problems** like declining products or unusual expenses\n" +
                  "- **Show who you owe** (suppliers, pending salaries) and your cash/UPI balance\n" +
                  "- **Give practical suggestions** to improve the business, backed by your own numbers\n" +
                  "- **Explain how to use the app**, step by step\n\nJust ask in your own words - for example, *\"How did we do yesterday?\"*"
                : "I'm here to help you at the store. I can:\n\n" +
                  "- Show **your billing total** for today\n" +
                  "- Tell you **which products need restocking** or are about to expire\n" +
                  "- Show **your attendance** and leave\n" +
                  "- Explain **how to do things in the app** - opening a shift, recording an expense, adding a product and more\n\nJust ask in your own words!";
            return new Reply(text, starter(mgmt));
        }
        if (WHO.matcher(q).matches()) {
            return new Reply("I'm **GroceryGPT**, your store's digital business assistant. I read your live sales, stock, expense and staff records " +
                "and turn them into plain answers and practical advice. I'm an AI, so I'll always tell you when something isn't recorded rather than guess.", starter(mgmt));
        }
        if (THANKS.matcher(q).matches()) {
            return new Reply(pick("You're welcome! Happy to help.", "Anytime, " + firstName + "! Happy to help.", "Glad I could help! Anything else you'd like to check?"),
                mgmt ? List.of("Do you have any suggestions?", "What is my total profit?") : List.of("Which products need restocking?"));
        }
        if (BYE.matcher(q).matches()) {
            return new Reply(pick("Goodbye, " + firstName + "! Have a great day at the store.", "See you soon, " + firstName + "! Wishing you good sales today."), List.of());
        }
        if (MOOD_GOOD.matcher(q).matches()) {
            return new Reply("Glad to hear it! Want me to pull up the numbers so you can see how it's adding up?",
                mgmt ? List.of("How are my sales today?", "What is my total profit?", "Do you have any suggestions?") : List.of("How are my sales today?"));
        }
        if (MOOD_BAD.matcher(q).matches()) {
            return new Reply("Sorry to hear that - slow days happen to every shop. Let me look at your numbers and see what might help. " +
                "Ask me *\"Do you have any suggestions?\"* and I'll check your sales trends, stock and expenses for anything worth acting on.",
                mgmt ? List.of("Do you have any suggestions?", "How are my sales today?", "Which products have declining sales?")
                    : List.of("Which products need restocking?", "Which products are expiring?"));
        }
        if (ACK.matcher(q).matches()) {
            return new Reply(pick("Great! Let me know if you'd like to check anything else.", "Sounds good. I'm here whenever you need me."),
                starter(mgmt));
        }
        return null;
    }

    private static List<String> starter(boolean mgmt) {
        return mgmt
            ? List.of("How are my sales today?", "What is my total profit?", "Which products need restocking?", "Who owes me money?", "Do you have any suggestions?")
            : List.of("How are my sales today?", "Which products need restocking?", "Which products are expiring?", "How do I open or close a cash shift?");
    }

    private static String timeGreeting() {
        int h = LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")).getHour();
        return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
    }

    private static String pick(String... options) { return options[ThreadLocalRandom.current().nextInt(options.length)]; }
}
