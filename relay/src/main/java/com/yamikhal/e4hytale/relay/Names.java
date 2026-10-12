package com.yamikhal.e4hytale.relay;

import java.util.List;
import java.util.random.RandomGenerator;

// friendly host names like "mossy-kweebec": an adjective and a noun, about 10 000 combinations. Short words people
// can read out over voice chat, no look-alikes, nothing rude in any combination
public final class Names {

    private static final List<String> ADJECTIVES = List.of(
            "amber", "ancient", "arctic", "autumn", "bold", "brave", "breezy", "bright", "brisk", "bronze",
            "calm", "candid", "cheery", "clever", "cloudy", "cosy", "crimson", "crisp", "curious", "dapper",
            "daring", "dawn", "dusty", "eager", "early", "emerald", "fabled", "fancy", "feral", "fierce",
            "fluffy", "foggy", "frosty", "gentle", "giant", "gilded", "glad", "golden", "grand", "happy",
            "hardy", "hidden", "hollow", "humble", "icy", "jolly", "keen", "kind", "lively", "lucky",
            "lunar", "mellow", "merry", "mighty", "misty", "mossy", "nimble", "noble", "odd", "olive",
            "plucky", "proud", "quick", "quiet", "rapid", "rocky", "rosy", "royal", "rusty", "sandy",
            "shady", "shiny", "silent", "silver", "sleepy", "snowy", "solar", "spicy", "spry", "stormy",
            "sturdy", "sunny", "swift", "tidy", "tiny", "toasty", "trusty", "velvet", "vivid", "wandering",
            "warm", "wild", "windy", "wise", "witty", "woolly", "young", "zany", "zesty", "zippy");

    private static final List<String> NOUNS = List.of(
            "anvil", "badger", "bard", "beacon", "bear", "beetle", "boar", "bramble", "brook", "cactus",
            "canyon", "castle", "cavern", "cedar", "comet", "coral", "crab", "crow", "crystal", "deer",
            "dragon", "duck", "eagle", "ember", "falcon", "feran", "fern", "ferret", "fjord", "flame",
            "forge", "fox", "frog", "gecko", "geyser", "glacier", "goat", "golem", "grove", "harbor",
            "hare", "hawk", "hedgehog", "heron", "hollow", "island", "jungle", "kweebec", "lagoon", "lantern",
            "lark", "llama", "lynx", "mammoth", "maple", "meadow", "mole", "moose", "mushroom", "otter",
            "outlander", "owl", "panda", "peak", "pebble", "pine", "puffin", "quarry", "rabbit", "raven",
            "reef", "river", "robin", "salmon", "scarak", "shrine", "skunk", "sparrow", "spire", "squirrel",
            "stag", "storm", "summit", "swamp", "temple", "thicket", "tiger", "toad", "torch", "trork",
            "tundra", "turtle", "valley", "volcano", "walrus", "willow", "wolf", "wren", "yak", "zephyr");

    private Names() {}

    public static String random(RandomGenerator random) {
        return ADJECTIVES.get(random.nextInt(ADJECTIVES.size())) + "-" + NOUNS.get(random.nextInt(NOUNS.size()));
    }

    // whether a label could be a name at all, so lookups of garbage never reach the registry
    public static boolean isWellFormed(String label) {
        int dash = label.indexOf('-');
        return dash > 0 && dash == label.lastIndexOf('-') && label.length() <= 32
                && label.chars().allMatch(c -> c == '-' || (c >= 'a' && c <= 'z'));
    }
}
