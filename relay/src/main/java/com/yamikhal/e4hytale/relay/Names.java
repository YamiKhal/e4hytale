package com.yamikhal.e4hytale.relay;

import java.util.List;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

// friendly host names like "mossy-kweebec": an adjective and a noun, 10 000 combinations, and once those run short a
// number on top ("mossy-kweebec-417", ten million). Short words people can read out over voice chat, nothing rude in
// any combination
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
            "hamlet", "hare", "hawk", "hedgehog", "heron", "island", "jungle", "kweebec", "lagoon", "lantern",
            "lark", "llama", "lynx", "mammoth", "maple", "meadow", "mole", "moose", "mushroom", "otter",
            "outlander", "owl", "panda", "peak", "pebble", "pine", "puffin", "quarry", "rabbit", "raven",
            "reef", "river", "robin", "salmon", "scarak", "shrine", "skunk", "sparrow", "spire", "squirrel",
            "stag", "storm", "summit", "swamp", "temple", "thicket", "tiger", "toad", "torch", "trork",
            "tundra", "turtle", "valley", "volcano", "walrus", "willow", "wolf", "wren", "yak", "zephyr");

    private static final Pattern WELL_FORMED = Pattern.compile("[a-z]{1,16}-[a-z]{1,16}(-[0-9]{1,3})?");

    private Names() {}

    // a two-word name, with a number when `numbered`
    public static String random(RandomGenerator random, boolean numbered) {
        String name = ADJECTIVES.get(random.nextInt(ADJECTIVES.size())) + "-" + NOUNS.get(random.nextInt(NOUNS.size()));
        return numbered ? name + "-" + random.nextInt(1000) : name;
    }

    // whether a label could be a name at all, so lookups of garbage never reach the registry
    public static boolean isWellFormed(String label) {
        return WELL_FORMED.matcher(label).matches();
    }
}
