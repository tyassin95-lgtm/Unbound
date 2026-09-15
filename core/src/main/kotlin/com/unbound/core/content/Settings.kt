package com.unbound.core.content

import com.unbound.core.model.Tone

/**
 * A setting is not just flavour text — each ships a hand-authored seed world (locations, people,
 * factions, an opening situation) so that a new campaign is immediately playable and *consistent*
 * before any model call happens.
 *
 * This is a deliberate cost and quality decision. Generating a whole world from the model on turn
 * zero is expensive, slow, and produces the blandest possible version of itself. Seeding from
 * authored content and letting the world grow outward from the player (§18) gives a better opening
 * and costs nothing.
 */
data class SeedLocation(
    val key: String,
    val name: String,
    val description: String,
    val type: String,
    val exits: Map<String, String> = emptyMap(),
    val hazards: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
)

data class SeedNpc(
    val key: String,
    val name: String,
    val age: Int,
    val gender: String,
    val occupation: String,
    val appearance: String,
    val personality: String,
    val wants: String,
    val secret: String,
    val locationKey: String,
    val factionKey: String? = null,
)

data class SeedFaction(
    val key: String,
    val name: String,
    val purpose: String,
    val objectives: List<String>,
    val reputation: String,
)

data class SeedThread(
    val title: String,
    val description: String,
    val stakes: String,
    val type: com.unbound.core.model.ThreadType,
    val importance: com.unbound.core.model.Importance,
    val involvedKeys: List<String> = emptyList(),
)

data class SeedWorld(
    val id: String,
    val name: String,
    val blurb: String,
    val region: String,
    val era: String,
    val summary: String,
    val currencyName: String,
    val weather: String,
    val socialHierarchy: String,
    val economy: String,
    val laws: String,
    val religion: String,
    val dangers: List<String>,
    val history: List<String>,
    val conflicts: List<String>,
    val specialRules: List<String> = emptyList(),
    val toneHint: Tone,
    val startLocationKey: String,
    val locations: List<SeedLocation>,
    val npcs: List<SeedNpc>,
    val factions: List<SeedFaction>,
    val threads: List<SeedThread>,
    val openingHooks: List<String>,
)

object Settings {

    val ALL: List<SeedWorld> = listOf(
        SeedWorld(
            id = "ashmarket",
            name = "Dark Industrial Fantasy",
            blurb = "A foundry city under permanent smoke, where the guilds own the water.",
            region = "Ashmarket, lower city",
            era = "the eleventh year of the Combine",
            summary = "Ashmarket runs on coke furnaces and cheap labour. The Founders' Combine owns the " +
                "foundries, the water rights and most of the magistrates. Everything is soot-coloured and " +
                "everyone is owed something by someone.",
            currencyName = "crowns",
            weather = "low yellow smoke, no wind",
            socialHierarchy = "Combine masters, then guild-sworn, then wage labour, then the unregistered.",
            economy = "Iron, coke and water rights. Scrip is more common than coin below the Ridge.",
            laws = "Combine courts. Debt is enforceable in labour. The watch is paid by the district.",
            religion = "The Kiln Saints — patron figures of trades, prayed to transactionally.",
            dangers = listOf("furnace collapses", "debt-press gangs", "lungrot", "the night watch on commission"),
            history = listOf(
                "The Combine broke the water strike eleven years ago and has held the wells since.",
                "The old river quarter burned and was never rebuilt; people live in it anyway.",
            ),
            conflicts = listOf(
                "The Combine is quietly buying the last independent foundry.",
                "Unregistered workers in the Cinders are organising for the first time since the strike.",
            ),
            toneHint = Tone.DARK,
            startLocationKey = "kettle",
            locations = listOf(
                SeedLocation(
                    "kettle", "The Black Kettle", "A low-ceilinged public house wedged under the foundry wall. " +
                        "Smoke comes through the bricks. Twenty people drink here and none of them are strangers to each other.",
                    "tavern", mapOf("out to the lane" to "lane", "back stairs" to "kettle_upstairs"),
                ),
                SeedLocation(
                    "kettle_upstairs", "Upstairs at the Kettle", "Three let rooms and a landing that creaks. " +
                        "The window looks straight onto the foundry wall.", "lodging", mapOf("down" to "kettle"),
                ),
                SeedLocation(
                    "lane", "Founders' Lane", "A steep cobbled lane running between the foundry wall and the tenements. " +
                        "Runoff water, always warm, always grey.", "street",
                    mapOf("into the Kettle" to "kettle", "downhill to the yards" to "yards", "uphill to the Ridge" to "ridge"),
                ),
                SeedLocation(
                    "yards", "The Casting Yards", "Open ground stacked with pig iron and sand moulds. " +
                        "Noise you feel in the teeth. Nobody stands still here.", "industrial",
                    mapOf("uphill" to "lane"), hazards = listOf("molten spill", "unsecured loads"),
                ),
                SeedLocation(
                    "ridge", "The Ridge", "Above the smoke line. Clean air, walled houses, private water. " +
                        "The watch here asks what your business is.", "district",
                    mapOf("downhill" to "lane"), hidden = listOf("The Combine's water ledgers are kept in the counting house here."),
                ),
            ),
            npcs = listOf(
                SeedNpc(
                    "mara", "Mara Venn", 43, "woman", "keeps the Black Kettle",
                    "Heavy-set, forearms like a smith's, iron-grey hair tied back, a silver ring worn thin on her left hand.",
                    "Watchful and dry. Remembers every tab. Slow to warm and slower to forgive.",
                    "To keep the Kettle out of Combine hands.",
                    "The Kettle's deed is held against a loan she cannot service.",
                    "kettle",
                ),
                SeedNpc(
                    "coll", "Coll Aster", 29, "man", "foundry hand, talks too much",
                    "Wiry, burn-scarred hands, permanent cough, a grin that arrives before the joke does.",
                    "Restless, generous, reckless with other people's secrets.",
                    "To be somebody in the new workers' meeting.",
                    "He has been informing to the Combine for four months and hates himself for it.",
                    "kettle", "cinders",
                ),
                SeedNpc(
                    "surrin", "Warden Surrin", 51, "woman", "district watch commander",
                    "Neat, square, uniform always clean in a city where nothing is. Grey hair under a flat cap.",
                    "Polite, procedural, entirely purchasable — but only at a price that holds.",
                    "To retire on the Ridge without incident.",
                    "She takes a standing payment from the Combine and has stopped pretending otherwise.",
                    "lane", "combine",
                ),
            ),
            factions = listOf(
                SeedFaction(
                    "combine", "The Founders' Combine", "Owns the foundries, the water and most of the magistrates.",
                    listOf("Acquire the last independent foundry", "Break the Cinders meeting before it spreads"),
                    "Feared, resented, universally dealt with.",
                ),
                SeedFaction(
                    "cinders", "The Cinders Meeting", "Unregistered workers organising in the burnt river quarter.",
                    listOf("Hold a general meeting without being raided", "Find someone on the Ridge who will talk"),
                    "Spoken of quietly. Half the lower city is sympathetic and afraid.",
                ),
            ),
            threads = listOf(
                SeedThread(
                    "The Kettle's debt", "Mara Venn owes more against the Black Kettle than she can service, " +
                        "and the note is held by a Combine factor.",
                    "If the note is called, the Kettle changes hands and the lower city loses its only neutral room.",
                    com.unbound.core.model.ThreadType.DEBT, com.unbound.core.model.Importance.HIGH, listOf("mara", "combine"),
                ),
            ),
            openingHooks = listOf(
                "You are three drinks into a tab you cannot pay when the door opens.",
                "Someone has left a sealed Combine letter on your table with your name on it.",
                "You arrived this morning and already someone has asked for you by name.",
            ),
        ),

        SeedWorld(
            id = "drowned", name = "Drowned Island Empire",
            blurb = "A sinking archipelago empire where salvage rights are worth killing for.",
            region = "Kess Anchorage, the Leeward Isles",
            era = "the ninth year of the rising water",
            summary = "The sea has taken four islands in a generation. What is left of the empire governs from " +
                "stilt-towns and argues over the salvage rights to its own drowned cities.",
            currencyName = "marks",
            weather = "warm rain, rising swell",
            socialHierarchy = "Salvage houses, then licensed divers, then the tide-poor who own nothing above water.",
            economy = "Salvage, salt, and the slow sale of an empire's own foundations.",
            laws = "Salvage law. Everything below the waterline belongs to whoever is licensed for that grid square.",
            religion = "The Drowned Choir — an intercessionary faith that holds the sea is owed something.",
            dangers = listOf("rip currents", "flooded structures", "unlicensed divers", "the sickness divers get"),
            history = listOf("The capital of Ossun went under in a single winter.", "The licensing grid was drawn in a week and has been fought over since."),
            conflicts = listOf("Two salvage houses both hold papers for the Ossun cathedral grid."),
            toneHint = Tone.CINEMATIC,
            startLocationKey = "anchorage",
            locations = listOf(
                SeedLocation("anchorage", "Kess Anchorage", "A town built on pilings over a drowned town. " +
                    "Everything smells of salt and wet rope. Below your feet, through gaps in the boards, roofs.",
                    "settlement", mapOf("down the ladder to the boats" to "boats", "along the walk" to "counting_house")),
                SeedLocation("boats", "The Boat Ladders", "Rope ladders down to a raft of moored craft. " +
                    "The swell moves everything together.", "docks", mapOf("up" to "anchorage", "out to the grid" to "ossun")),
                SeedLocation("counting_house", "The Kess Counting House", "Where salvage licences are issued, recorded and disputed. " +
                    "Loud, dry, and the driest place in town.", "civic", mapOf("out" to "anchorage")),
                SeedLocation("ossun", "The Ossun Grid", "Open water over a drowned cathedral district. " +
                    "At low tide the spires break the surface.", "underwater",
                    mapOf("back to the anchorage" to "boats"), hazards = listOf("collapsing masonry", "currents through the nave")),
            ),
            npcs = listOf(
                SeedNpc("iselle", "Iselle Rook", 36, "woman", "licensed diver",
                    "Sun-bleached, powerfully built, a diver's ruined ears, a rope burn round the throat.",
                    "Terse, superstitious, absolutely reliable underwater and nowhere else.",
                    "To dive the cathedral grid before the other house does.",
                    "She has already been down there, unlicensed, and saw something she has not reported.", "boats"),
                SeedNpc("ferrant", "Clerk Ferrant", 48, "man", "licence clerk",
                    "Pale, soft-handed, ink to the second knuckle, spectacles mended with wire.",
                    "Fussy, frightened, desperate to be on the right side of whoever wins.",
                    "To survive the dispute with his post intact.",
                    "He issued both conflicting licences, knowingly, for money.", "counting_house"),
            ),
            factions = listOf(
                SeedFaction("valdt", "House Valdt", "Old salvage money, three generations deep.",
                    listOf("Establish sole claim to the Ossun grid"), "Respectable, and ruthless about it."),
                SeedFaction("newtide", "The Newtide Company", "New money, better boats, no manners.",
                    listOf("Break House Valdt's claim", "Recruit every diver worth having"), "Resented and well paid."),
            ),
            threads = listOf(
                SeedThread("The Ossun grid dispute", "Two salvage houses hold valid-looking papers for the same drowned district.",
                    "Whoever loses is finished, and the clerk who issued both will be the one who hangs.",
                    com.unbound.core.model.ThreadType.ECONOMIC, com.unbound.core.model.Importance.HIGH, listOf("ferrant", "valdt", "newtide")),
            ),
            openingHooks = listOf("A diver you do not know has asked for you by name, at the ladders, before dawn.",
                "The counting house has your name on a list and will not say which list."),
        ),

        SeedWorld(
            id = "cyber", name = "Cyberpunk Megacity",
            blurb = "Forty million people, nine arcologies, and no clean water that is not metered.",
            region = "Sector 9, New Bellum",
            era = "post-consolidation",
            summary = "Nine corporate arcologies sit on a city that predates them. Between the towers, Sector 9 " +
                "runs on grey-market clinics, metered utilities and favours.",
            currencyName = "credits",
            weather = "acidic drizzle, sodium haze",
            socialHierarchy = "Arcology-contracted, then sector-registered, then the unlisted.",
            economy = "Data, biologics, and rent.",
            laws = "Corporate jurisdiction inside the arcologies; sector marshals outside, badly paid.",
            religion = "Nothing organised. Several things marketed.",
            dangers = listOf("unlisted status checks", "clinic infections", "drone sweeps", "debt collection algorithms"),
            history = listOf("The consolidation took seven years and nobody counts the dead publicly."),
            conflicts = listOf("Two arcologies are quietly at war over a biologics patent."),
            specialRules = listOf("Neural implants are common and can be compromised remotely."),
            toneHint = Tone.DARK,
            startLocationKey = "clinic",
            locations = listOf(
                SeedLocation("clinic", "The Halide Clinic", "A grey-market surgery behind a laundrette. " +
                    "Clean in the way a kitchen is clean. Three chairs, two occupied.", "clinic",
                    mapOf("out to the arcade" to "arcade")),
                SeedLocation("arcade", "Sub-level Arcade", "Two hundred metres of stalls under the transit deck. " +
                    "Everything is sold here and most of it works.", "market",
                    mapOf("into the clinic" to "clinic", "up to the deck" to "deck")),
                SeedLocation("deck", "Transit Deck 9", "Open to the rain. Trains every ninety seconds. " +
                    "Status checks at both ends.", "transit", mapOf("down" to "arcade"), hazards = listOf("status checks")),
            ),
            npcs = listOf(
                SeedNpc("halide", "Dr. Ester Halide", 52, "woman", "unlicensed surgeon",
                    "Small, precise, close-cropped white hair, surgical loupes pushed up on her forehead.",
                    "Brisk, unsentimental, genuinely good at the work.",
                    "To keep operating without a licence for one more year.",
                    "She is being blackmailed into installing compromised implants.", "clinic"),
                SeedNpc("nix", "Nix", 24, "nonbinary", "fixer, arcade level",
                    "Thin, restless, constantly-changing hair colour, a cheap ocular that clicks when it focuses.",
                    "Fast-talking, loyal to about four people, terrified of being unlisted.",
                    "To get sector registration before the next sweep.",
                    "They sold someone's location last month and that person is dead.", "arcade"),
            ),
            factions = listOf(
                SeedFaction("vantek", "Vantek Arcology", "Biologics and contract medicine.",
                    listOf("Secure the disputed patent", "Shut down grey-market clinics in Sector 9"), "Omnipresent, impersonal."),
                SeedFaction("marshals", "Sector Marshals", "Nominal law outside the towers.",
                    listOf("Meet quota on unlisted detentions"), "Underpaid and widely bought."),
            ),
            threads = listOf(
                SeedThread("Compromised implants", "Someone is forcing a back-street surgeon to install implants with a back door.",
                    "Everyone who went through that clinic in the last two months is exposed.",
                    com.unbound.core.model.ThreadType.CRIMINAL, com.unbound.core.model.Importance.HIGH, listOf("halide", "vantek")),
            ),
            openingHooks = listOf("You are second in the chair queue and the first patient has not moved in a while.",
                "Your status ping came back amber this morning and nobody will tell you why."),
        ),

        SeedWorld(
            id = "frontier", name = "Frontier Desert",
            blurb = "One rail line, four wells, and a company that owns all of them.",
            region = "Sabre Wash, the Territory",
            era = "eighteen years after the survey",
            summary = "A rail town at the edge of surveyed land. Water comes from four company wells. " +
                "Everything else comes on the Tuesday train.",
            currencyName = "dollars",
            weather = "dry heat, dust on the wind",
            socialHierarchy = "Company men, then landholders, then hands, then anyone the company has not registered.",
            economy = "Cattle, copper, and water sold by the barrel.",
            laws = "A circuit judge every six weeks. A marshal in between, when he feels like it.",
            religion = "A chapel, sparsely attended, and a great deal of private superstition.",
            dangers = listOf("running out of water", "claim disputes settled privately", "the country beyond the wash"),
            history = listOf("The survey came through eighteen years ago and the company came with it."),
            conflicts = listOf("The Bellweather claim has been contested for two years and the judge is due."),
            toneHint = Tone.SERIOUS,
            startLocationKey = "saloon",
            locations = listOf(
                SeedLocation("saloon", "The Dry Measure", "One long room, a plank bar, a piano nobody plays. " +
                    "Cooler than outside by about four degrees.", "saloon",
                    mapOf("out to the street" to "street", "upstairs" to "rooms")),
                SeedLocation("rooms", "Rooms above the Measure", "Four rooms, thin walls, a washstand each.", "lodging", mapOf("down" to "saloon")),
                SeedLocation("street", "Sabre Wash Main Street", "Two hundred yards of packed dirt between the depot and the well house.",
                    "street", mapOf("into the Measure" to "saloon", "to the depot" to "depot", "to the well house" to "wellhouse")),
                SeedLocation("depot", "The Rail Depot", "A platform, a water tower, and a stationmaster who knows everything.",
                    "transit", mapOf("to the street" to "street")),
                SeedLocation("wellhouse", "Company Well House", "Locked, pumped, metered, guarded.", "industrial",
                    mapOf("to the street" to "street"), hidden = listOf("Well three has been running dry for a month and the company has not said so.")),
            ),
            npcs = listOf(
                SeedNpc("juno", "Juno Castellar", 39, "woman", "keeps the Dry Measure",
                    "Broad, sun-dark, a long braid going silver, a pistol behind the bar she has used twice.",
                    "Even-handed to a fault. Will throw anyone out and hold no grudge about it.",
                    "To stay neutral between the company and the claim-holders.",
                    "She has been letting a wanted man sleep in room four.", "saloon"),
                SeedNpc("bellweather", "Aurel Bellweather", 60, "man", "contested claim-holder",
                    "Thin, upright, sun-ruined skin, a good hat kept carefully.",
                    "Proud, stubborn, unable to let anything go.",
                    "To hold the claim until the judge arrives.",
                    "His original survey papers were lost and what he holds is a copy he made himself.", "street"),
            ),
            factions = listOf(
                SeedFaction("company", "Sabre Land & Water", "Owns the wells, the depot and the survey records.",
                    listOf("Consolidate the contested claims before the judge arrives"), "Necessary and disliked."),
                SeedFaction("holders", "The Claim-holders", "Loose association of independent landholders.",
                    listOf("Get the Bellweather claim upheld"), "Sympathetic, disorganised."),
            ),
            threads = listOf(
                SeedThread("The Bellweather claim", "A contested land claim comes before the circuit judge within the month.",
                    "If it falls, every independent claim in the wash falls with it.",
                    com.unbound.core.model.ThreadType.POLITICAL, com.unbound.core.model.Importance.HIGH, listOf("bellweather", "company")),
            ),
            openingHooks = listOf("The Tuesday train brought someone asking after you.",
                "There is no water in the trough outside and nobody will say why."),
        ),

        SeedWorld(
            id = "academy", name = "Supernatural Academy",
            blurb = "A school for people whose talents are inconvenient, in a building that is not entirely inert.",
            region = "Corvane House, the north moors",
            era = "the present, more or less",
            summary = "Corvane House teaches eleven students and employs nine staff. The building responds to " +
                "what happens inside it, which the prospectus does not mention.",
            currencyName = "pounds",
            weather = "wet fog off the moor",
            socialHierarchy = "Staff, senior students, junior students, and whatever the house is.",
            economy = "Endowment, quietly dwindling.",
            laws = "House rules, enforced unevenly. Outside law does not come this far.",
            religion = "A chapel nobody uses and a great many rules that resemble ritual.",
            dangers = listOf("the east wing", "untrained talents", "the moor at night"),
            history = listOf("Three students left in one night, forty years ago, and the east wing was closed."),
            conflicts = listOf("The headmaster wants the east wing opened. Nobody else does."),
            specialRules = listOf("The house alters its own layout in response to strong emotion. Doors are not reliable."),
            toneHint = Tone.SLOW_BURN,
            startLocationKey = "hall",
            locations = listOf(
                SeedLocation("hall", "The Long Hall", "Panelled, cold, portraits of people the school no longer discusses. " +
                    "Doors at both ends, and sometimes a third.", "interior",
                    mapOf("to the library" to "library", "to the dormitories" to "dorms", "east" to "east_wing")),
                SeedLocation("library", "Corvane Library", "Two floors, one ladder, and a catalogue that is wrong on purpose.",
                    "library", mapOf("to the hall" to "hall"), hidden = listOf("The 1884 register is missing three pages.")),
                SeedLocation("dorms", "The Dormitories", "Six rooms, four occupied. Everything creaks.", "lodging", mapOf("to the hall" to "hall")),
                SeedLocation("east_wing", "The East Wing", "Closed for forty years. The door is not locked, which is worse.",
                    "interior", mapOf("back to the hall" to "hall"), hazards = listOf("the layout does not stay put")),
            ),
            npcs = listOf(
                SeedNpc("greaves", "Dr. Amelia Greaves", 57, "woman", "head of house",
                    "Severe, elegant, silver-streaked hair pinned tight, always gloved.",
                    "Formal, protective, evasive about anything before 1985.",
                    "To keep the east wing closed and the school open.",
                    "She was one of the three students who left that night.", "library"),
                SeedNpc("piet", "Piet Auber", 17, "man", "senior student",
                    "Tall, anxious, bitten nails, hair he cuts himself badly.",
                    "Earnest, nosy, incapable of leaving a question alone.",
                    "To find out what is in the east wing.",
                    "He has already been inside twice and cannot account for the second time.", "hall"),
            ),
            factions = listOf(
                SeedFaction("staff", "The Staff", "Nine adults with incompatible ideas about duty of care.",
                    listOf("Keep the school's funding", "Prevent another incident"), "Trusted by parents, not by students."),
                SeedFaction("students", "The Senior Students", "Four of them, and they talk to each other more than to staff.",
                    listOf("Find out what happened in 1985"), "Watched."),
            ),
            threads = listOf(
                SeedThread("The east wing", "A wing closed for forty years is no longer staying closed.",
                    "Whatever ended three students in 1985 has started again.",
                    com.unbound.core.model.ThreadType.MYSTERY, com.unbound.core.model.Importance.CRITICAL, listOf("greaves", "piet")),
            ),
            openingHooks = listOf("The east door is open, and it was not open at supper.",
                "Someone has left the 1884 register on your bed, with pages missing."),
        ),

        SeedWorld(
            id = "court", name = "Decadent Royal Court",
            blurb = "A court where everything is decided at parties and nobody says what they mean.",
            region = "The Winter Palace at Ansel",
            era = "the fourteenth year of a long reign",
            summary = "The king is old, the succession is unsettled, and four hundred people who cannot leave the " +
                "palace spend every evening finding out who is winning.",
            currencyName = "sovereigns",
            weather = "hard frost, clear nights",
            socialHierarchy = "Blood, then favour, then money, then usefulness. Favour moves fastest.",
            economy = "Patronage. Almost nobody here earns anything.",
            laws = "The king's word, the chamberlain's interpretation, and duelling conventions everyone pretends are illegal.",
            religion = "Formal, elaborate, believed in by perhaps a dozen people.",
            dangers = listOf("poison", "ruinous gossip", "being seen with the wrong person", "duels"),
            history = listOf("The king's brother died in a hunting accident that everyone attended."),
            conflicts = listOf("Two candidates for the succession, and the king has named neither."),
            toneHint = Tone.SLOW_BURN,
            startLocationKey = "gallery",
            locations = listOf(
                SeedLocation("gallery", "The Long Gallery", "Mirrors both sides, three hundred candles, everyone visible to everyone.",
                    "interior", mapOf("to the card room" to "cards", "to the terrace" to "terrace", "to the private wing" to "wing")),
                SeedLocation("cards", "The Card Room", "Where money changes hands and so does everything else.", "interior", mapOf("to the gallery" to "gallery")),
                SeedLocation("terrace", "The Winter Terrace", "Freezing, unlit, and the only place two people can speak unheard.",
                    "exterior", mapOf("inside" to "gallery")),
                SeedLocation("wing", "The Private Wing", "Guarded. Invitation only, and the invitation is never written down.",
                    "interior", mapOf("to the gallery" to "gallery"), hidden = listOf("The king has not left these rooms in nine days.")),
            ),
            npcs = listOf(
                SeedNpc("vasquine", "Lady Vasquine Orr", 34, "woman", "the king's niece, and a candidate",
                    "Striking, dark red hair dressed high, a mourning ring she has worn since the hunting accident.",
                    "Gracious, precise, and frightening to people who are paying attention.",
                    "The succession, without having to ask for it.",
                    "She knows exactly how her father died at that hunt.", "gallery"),
                SeedNpc("chamberlain", "Chamberlain Pell", 63, "man", "runs the palace",
                    "Stooped, immaculate, a voice pitched to carry exactly as far as he wants.",
                    "Discreet, exhausted, the only person here who does actual work.",
                    "An orderly succession, whoever it is.",
                    "He has been forging the king's signature for six weeks.", "wing"),
            ),
            factions = listOf(
                SeedFaction("orr", "The Orr Interest", "Those backing Lady Vasquine.",
                    listOf("Secure a naming before the king weakens further"), "Ascendant, and knows it."),
                SeedFaction("regency", "The Regency Party", "Those who would rather nobody was named at all.",
                    listOf("Delay any naming", "Discredit the Orr interest"), "Cautious, well-funded."),
            ),
            threads = listOf(
                SeedThread("The unnamed succession", "The king has named no heir and has not been seen in nine days.",
                    "If he dies unnamed, the palace decides it, and the palace decides it violently.",
                    com.unbound.core.model.ThreadType.POLITICAL, com.unbound.core.model.Importance.CRITICAL, listOf("vasquine", "chamberlain")),
            ),
            openingHooks = listOf("A note was pressed into your hand in the gallery and you have not read it yet.",
                "You have been invited to the private wing, which does not happen."),
        ),
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}
