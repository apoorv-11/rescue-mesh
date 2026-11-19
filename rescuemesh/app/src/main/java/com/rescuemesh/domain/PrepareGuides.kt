package com.rescuemesh.domain

/**
 * Prepare hub (Prompt 10): bundled offline guides — disaster
 * awareness for BEFORE an emergency. SOS and the mesh are for the
 * emergency itself; these guides are for preparing for it.
 *
 * Android-free and bundled in the app: no download, no server call,
 * no login — every guide opens in airplane mode. Content is English
 * only for now (Hindi is later work). No "SIMULATED" label: this is
 * real guidance, not demo data — every guide names its source.
 *
 * SOURCES (verified, never written from memory — see
 * prepare-first-aid-source-2026-11-16.md):
 * - Guides 1–5 (hazards): NDMA "Be Smart Be Prepared" pocketbook.
 * - Guide 6 (first aid): IFRC International First Aid, Resuscitation
 *   and Education Guidelines 2020 (primary) + St John Ambulance UK
 *   free first-aid advice (lay wording). The 1910s "Indian Manual of
 *   First Aid" found on archive.org is NOT used — obsolete.
 * - Guide 7 (go-bag): item list is NDMA's; NDMA gives NO quantities.
 *   The water quantity, where given, is labelled as the American Red
 *   Cross planning figure (1 gallon ≈ 3.8 litres per person per day),
 *   never as NDMA's.
 * - Guide 8 (family plan): NDMA pocketbook + NCDC household checklist.
 * - Guide 9 (SOS not sending): the app's own flow + 112 (India's
 *   national emergency number).
 *
 * Every guide follows ONE template: What it is / Before / During /
 * After / Do NOT / When to send SOS. Summary cap (300 chars) follows
 * Doc 2's AwarenessItem rule so a future server sync can reuse the
 * same shape (Phase 4 — NOT this build: no YouTube/RSS, no server
 * sync for guides).
 */
data class GuideSection(
    val title: String,
    val bullets: List<String>
)

data class Guide(
    val id: String,
    val title: String,
    /** Hazard chip value, or "" when the guide has no hazard (guide 9). */
    val hazard: String,
    val summary: String,
    val sections: List<GuideSection>,
    val source: String
)

object PrepareGuides {
    const val HAZARD_FLOOD = "flood"
    const val HAZARD_EARTHQUAKE = "earthquake"
    const val HAZARD_CYCLONE = "cyclone"
    const val HAZARD_FIRE = "fire"
    const val HAZARD_LANDSLIDE = "landslide"
    const val HAZARD_FIRST_AID = "first-aid"
    const val HAZARD_FAMILY_PLAN = "family-plan"

    /** All hazard chips shown on the list, in display order. */
    val HAZARDS: List<String> = listOf(
        HAZARD_FLOOD, HAZARD_EARTHQUAKE, HAZARD_CYCLONE, HAZARD_FIRE,
        HAZARD_LANDSLIDE, HAZARD_FIRST_AID, HAZARD_FAMILY_PLAN
    )

    fun hazardLabel(hazard: String): String = when (hazard) {
        HAZARD_FLOOD -> "Flood"
        HAZARD_EARTHQUAKE -> "Earthquake"
        HAZARD_CYCLONE -> "Cyclone"
        HAZARD_FIRE -> "Fire"
        HAZARD_LANDSLIDE -> "Landslide"
        HAZARD_FIRST_AID -> "First aid"
        HAZARD_FAMILY_PLAN -> "Family plan"
        else -> hazard
    }

    private fun s(title: String, vararg bullets: String) =
        GuideSection(title, bullets.toList())

    val ALL: List<Guide> = listOf(
        Guide(
            id = "flood",
            title = "Flood — what to do",
            hazard = HAZARD_FLOOD,
            summary = "Rising water is fast and deceptive. Get to high ground early, never walk or drive through floodwater, and keep drinking water safe.",
            sections = listOf(
                s(
                    "What it is",
                    "Heavy rain, a swollen river or blocked drains can flood streets and homes with little warning.",
                    "Even shallow moving water is strong enough to knock a person down; floodwater hides drains, debris and live wires."
                ),
                s(
                    "Before",
                    "Know whether your area floods, and keep your go-bag ready (see the Go-bag checklist).",
                    "Keep important documents in a waterproof bag, up high.",
                    "Follow IMD and local authority warnings; charge your phone while power is on."
                ),
                s(
                    "During",
                    "Move to higher ground at once — do not wait to see how high the water gets.",
                    "Never walk or drive through floodwater. Turn around; find another route or wait on high ground.",
                    "Avoid underpasses, basements and riverbanks. Stay away from fallen power lines.",
                    "If your home floods, switch off electricity at the main switch only if you can do so from a dry place."
                ),
                s(
                    "After",
                    "Return only when authorities say it is safe.",
                    "Do not drink tap water until it is declared safe — boil it or use sealed bottled water.",
                    "Watch for snakes, debris and weakened walls or floors. Clean and dry your home to prevent disease."
                ),
                s(
                    "Do NOT",
                    "Do not walk or drive through floodwater, however shallow it looks.",
                    "Do not touch electrical equipment while standing in water.",
                    "Do not drink floodwater or water of unknown safety."
                ),
                s(
                    "When to send SOS",
                    "Send SOS if anyone is trapped by rising water, injured, or cut off with no safe way out.",
                    "If SOS does not send, call 112."
                )
            ),
            source = "NDMA — Be Smart Be Prepared pocketbook (Floods / Urban floods)"
        ),
        Guide(
            id = "earthquake",
            title = "Earthquake — what to do",
            hazard = HAZARD_EARTHQUAKE,
            summary = "An earthquake gives no warning. Drop, Cover and Hold On where you are — most injuries come from falling objects, not collapsing buildings.",
            sections = listOf(
                s(
                    "What it is",
                    "Sudden shaking of the ground. It can last seconds, and aftershocks can follow.",
                    "Falling objects, glass and toppling furniture cause most injuries indoors."
                ),
                s(
                    "Before",
                    "Fix heavy furniture, shelves and water heaters to walls; keep heavy items low.",
                    "Know the safe spots in each room: under a sturdy table or against an inside wall, away from windows.",
                    "Keep your go-bag and a torch where you can reach them in the dark."
                ),
                s(
                    "During",
                    "Drop to the ground, take Cover under a sturdy table, and Hold On until the shaking stops.",
                    "If no table is near, crouch against an inside wall and protect your head and neck with your arms.",
                    "Stay indoors until the shaking stops. Do not use lifts.",
                    "If you are outside, move to open ground, away from buildings, trees and power lines."
                ),
                s(
                    "After",
                    "Expect aftershocks — Drop, Cover and Hold On again for each one.",
                    "Check yourself and others for injuries. Check for gas leaks, damaged wiring and fires before switching anything on.",
                    "Leave a damaged building carefully and stay out until it is checked."
                ),
                s(
                    "Do NOT",
                    "Do not run outside during the shaking.",
                    "Do not stand in doorways — a sturdy table protects you better.",
                    "Do not light matches or candles if you smell gas."
                ),
                s(
                    "When to send SOS",
                    "Send SOS if anyone is trapped, badly injured, or a building is unsafe to stay in.",
                    "If SOS does not send, call 112."
                )
            ),
            source = "NDMA — Be Smart Be Prepared pocketbook (Earthquakes)"
        ),
        Guide(
            id = "cyclone",
            title = "Cyclone — what to do",
            hazard = HAZARD_CYCLONE,
            summary = "A cyclone brings very strong winds, heavy rain and storm surge. Shelter indoors in the strongest part of your home and stay there until the all-clear.",
            sections = listOf(
                s(
                    "What it is",
                    "A rotating storm with damaging winds, torrential rain and a rise in sea level (storm surge) near the coast.",
                    "Flying debris and falling trees are the main dangers; the calm eye is temporary — the storm returns."
                ),
                s(
                    "Before",
                    "Follow IMD cyclone warnings and local authority instructions; know your nearest cyclone shelter.",
                    "Board up or tape windows, tie down loose items outside, and trim weak branches.",
                    "Stock drinking water, dry food, medicines and torches for at least a few days; charge your phone."
                ),
                s(
                    "During",
                    "Stay indoors in the strongest, smallest room, away from windows and glass doors.",
                    "If the roof lifts or windows break, shelter under a sturdy table or mattress and protect your head.",
                    "Do not go outside during the calm eye — the wind will return from the other direction.",
                    "Keep away from the coast and low-lying areas during storm surge."
                ),
                s(
                    "After",
                    "Wait for the official all-clear before going out.",
                    "Stay clear of fallen power lines, damaged buildings and flooded roads.",
                    "Boil drinking water if the supply is suspect."
                ),
                s(
                    "Do NOT",
                    "Do not go outside during the storm, even in the calm eye.",
                    "Do not shelter under trees or near the sea during the storm.",
                    "Do not touch fallen power lines."
                ),
                s(
                    "When to send SOS",
                    "Send SOS if anyone is trapped, injured, or your shelter is failing.",
                    "If SOS does not send, call 112."
                )
            ),
            source = "NDMA — Be Smart Be Prepared pocketbook (Cyclones); IMD warnings"
        ),
        Guide(
            id = "fire",
            title = "Fire — what to do",
            hazard = HAZARD_FIRE,
            summary = "Fire spreads fast and smoke kills before flames reach you. Get out, stay low under smoke, and never go back inside for belongings.",
            sections = listOf(
                s(
                    "What it is",
                    "A fire in a home or building can fill rooms with hot, toxic smoke in minutes.",
                    "Smoke rises, so the cleanest air is near the floor."
                ),
                s(
                    "Before",
                    "Know two ways out of every room and agree a meeting point outside with your family.",
                    "Keep matches and lighters away from children; never leave cooking or candles unattended.",
                    "Keep a torch and your go-bag near the exit you would use."
                ),
                s(
                    "During",
                    "Get out at once — shout to warn others, but do not stop to collect things.",
                    "Stay low under smoke; cover your nose and mouth with a wet cloth if you can.",
                    "Feel a closed door with the back of your hand: if it is hot, do not open it — use the other way out.",
                    "If your clothes catch fire: Stop, Drop and Roll until the flames are out.",
                    "Never use a lift in a fire."
                ),
                s(
                    "After",
                    "Stay outside until firefighters say it is safe to re-enter.",
                    "Do not switch on lights or appliances in a burnt room until it is checked.",
                    "Get medical help for any burn or smoke inhalation, even if it seems minor."
                ),
                s(
                    "Do NOT",
                    "Do not go back inside for belongings, pets or documents.",
                    "Do not open a hot door, and do not use lifts.",
                    "Do not pour water on a cooking-oil fire."
                ),
                s(
                    "When to send SOS",
                    "Send SOS if anyone is trapped, injured, or you cannot get out safely.",
                    "If SOS does not send, call 112 and the fire service."
                )
            ),
            source = "NDMA — Be Smart Be Prepared pocketbook (Fire)"
        ),
        Guide(
            id = "landslide",
            title = "Landslide — what to do",
            hazard = HAZARD_LANDSLIDE,
            summary = "Heavy rain can loosen slopes in minutes. Learn the warning signs, move away from the slope at once, and never cross a slide path.",
            sections = listOf(
                s(
                    "What it is",
                    "Rock, earth and debris sliding down a slope, usually after heavy or long rain, and sometimes after an earthquake.",
                    "A slide can block roads and rivers and bury homes."
                ),
                s(
                    "Before",
                    "If you live near a steep slope, know the warning signs and your safe route away from it.",
                    "Keep drains and channels clear so rainwater does not build up behind the slope.",
                    "Listen for official warnings during heavy rain."
                ),
                s(
                    "During",
                    "Warning signs: new cracks in the ground or walls, tilting trees or poles, sudden muddy water in streams, or a rumbling sound.",
                    "Move away from the slope at once, to higher ground and sideways from the slide path — never along it.",
                    "Warn others as you go. Do not try to cross a moving slide."
                ),
                s(
                    "After",
                    "Stay away from the slide area — more slides can follow, and the ground may still be moving.",
                    "Watch for flooding if the slide has blocked a stream or river.",
                    "Do not return until authorities say the area is safe."
                ),
                s(
                    "Do NOT",
                    "Do not cross or walk on a slide, and do not go back for belongings.",
                    "Do not shelter at the foot of a steep slope during heavy rain."
                ),
                s(
                    "When to send SOS",
                    "Send SOS if anyone is buried, trapped or injured, or a slide has cut off your escape.",
                    "If SOS does not send, call 112."
                )
            ),
            source = "NDMA — Be Smart Be Prepared pocketbook (Landslides)"
        ),
        Guide(
            id = "first-aid",
            title = "First-aid basics",
            hazard = HAZARD_FIRST_AID,
            summary = "The first minutes matter. Call for help first, stop severe bleeding with pressure, cool burns with running water, and keep the person safe until help arrives.",
            sections = listOf(
                s(
                    "What it is",
                    "Simple, immediate care for an injured or ill person until trained help arrives.",
                    "You do not need special equipment — use whatever resources are available. A lack of equipment must never stop you helping."
                ),
                s(
                    "Before",
                    "Learn first aid from a proper course — this guide is only a reminder, not training.",
                    "Keep a first-aid kit where you can reach it, and know your local emergency number: 112.",
                    "Save 112 in your phone and teach children when to call it."
                ),
                s(
                    "During",
                    "Call 112 as soon as help is thought needed. If you are on a phone, put it on speaker so you can give care and talk at the same time.",
                    "Make the scene safe first — do not become a second casualty.",
                    "Check the person: are they awake, breathing, bleeding? Treat the most dangerous problem first.",
                    "Severe bleeding: press firmly on the wound with a clean cloth and keep pressing. Tourniquets and haemostatic agents only where permitted locally.",
                    "Cuts and grazes: clean with clean (potable) water, preferably running water from a tap, then cover the wound.",
                    "Burns: cool with running water for at least 10 minutes, ideally 20 minutes. Do not put creams, toothpaste or ice on a burn.",
                    "Choking: if the person can cough, encourage coughing. If they cannot breathe or speak, give firm back blows; if that fails, follow the advice of the 112 dispatcher.",
                    "Broken bones or sprains: keep the injured part still and supported; do not try to straighten it. If the person is unconscious but breathing, put them in the recovery position."
                ),
                s(
                    "After",
                    "Stay with the person until help arrives and keep them warm and calm.",
                    "Tell the responders what happened and what you did.",
                    "Seek medical care even for injuries that look small — bleeding, burns and head injuries can worsen."
                ),
                s(
                    "Do NOT",
                    "Do not move a person with a suspected spine injury unless they are in danger.",
                    "Do not give food or drink to an unconscious person.",
                    "Do not film an emergency incident, especially if it blocks responders.",
                    "This is not a substitute for first-aid training. Follow official advice and call 112."
                ),
                s(
                    "When to send SOS",
                    "Send SOS for any serious injury, heavy bleeding, burns, choking that does not clear, or an unconscious person.",
                    "If SOS does not send, call 112."
                )
            ),
            source = "IFRC International First Aid, Resuscitation and Education Guidelines 2020 (primary); St John Ambulance UK free first-aid advice (lay wording)"
        ),
        Guide(
            id = "go-bag",
            title = "Go-bag checklist",
            hazard = HAZARD_FAMILY_PLAN,
            summary = "One bag, ready to grab, with water, food, documents, medicines and light for at least 3 days. Pack it now — not when the warning comes.",
            sections = listOf(
                s(
                    "What it is",
                    "A bag kept packed and within reach so your family can leave home at short notice.",
                    "One bag per person where possible; keep it near the door you would leave by."
                ),
                s(
                    "Before",
                    "Water and dry food for at least 3 days; water-purifying tablets or powder if you have them.",
                    "Battery torch and radio, with extra batteries.",
                    "First-aid kit and a first-aid manual; essential medicines.",
                    "Important documents (ID, insurance, medical records) in a waterproof bag, plus copies.",
                    "Cash in small notes; a knife and can opener; thick rope; sturdy shoes.",
                    "Candles and waterproof matches.",
                    "Water quantity, read carefully: NDMA lists sealed water as an item but gives NO quantity for it. If you want a number to plan by: the American Red Cross planning figure is 1 gallon (about 3.8 litres) of water per person per day. That figure is the American Red Cross's, not NDMA's.",
                    "Store water in sealed, clean containers and replace it regularly."
                ),
                s(
                    "During",
                    "Grab the bag and your phone; leave by your planned route.",
                    "Check that everyone — children, elders, neighbours who need help — is with you.",
                    "Take the bag even if you expect to return soon; conditions change fast."
                ),
                s(
                    "After",
                    "Restock anything you used as soon as you can, so the bag is ready again.",
                    "Check expiry dates on food, water and medicines every few months."
                ),
                s(
                    "Do NOT",
                    "Do not pack the bag only when a warning comes — it will be too late.",
                    "Do not fill it with things you will not need; it must be light enough to carry."
                ),
                s(
                    "When to send SOS",
                    "Send SOS if you are trapped, injured, or cannot leave safely even with the bag.",
                    "If SOS does not send, call 112."
                )
            ),
            source = "NDMA — Build a Kit (item list; NDMA gives no quantities). Water figure: American Red Cross planning figure"
        ),
        Guide(
            id = "family-plan",
            title = "Family plan",
            hazard = HAZARD_FAMILY_PLAN,
            summary = "Agree now where to meet and who to call if you are separated. Two meeting points, one out-of-area contact, and everyone knowing how to switch off gas, power and water.",
            sections = listOf(
                s(
                    "What it is",
                    "A short plan your whole family knows by heart: where to meet, who to call, and what each person does.",
                    "Write it down and keep a copy in your go-bag and on your phone."
                ),
                s(
                    "Before",
                    "Pick TWO meeting points: one just outside your home, one outside your neighbourhood in case you cannot return.",
                    "Pick ONE out-of-area contact (a relative or friend in another town) — local lines may be busy, long-distance calls often get through.",
                    "Teach everyone how to switch off gas, electricity and water, and where the switches are.",
                    "Teach children their full name, address, and how to call 112.",
                    "Keep phone numbers on paper too — phones run out of charge."
                ),
                s(
                    "During",
                    "Follow the plan: go to the meeting point and call your out-of-area contact.",
                    "If you are separated, do not keep re-entering a dangerous area to search — tell responders.",
                    "Listen for official alerts (in India, NDMA's SACHET system sends IMD and state warnings to phones)."
                ),
                s(
                    "After",
                    "Meet at the agreed point and check everyone in with your out-of-area contact.",
                    "Do not return home until authorities say it is safe.",
                    "After any real event, sit together and fix what did not work in the plan."
                ),
                s(
                    "Do NOT",
                    "Do not assume everyone knows the plan — practise it together, including children.",
                    "Do not rely on one meeting point or one phone."
                ),
                s(
                    "When to send SOS",
                    "Send SOS if anyone in the family is trapped, injured or missing after you have followed the plan.",
                    "If SOS does not send, call 112."
                )
            ),
            source = "NDMA — Be Smart Be Prepared pocketbook; NCDC household checklist"
        ),
        Guide(
            id = "sos-not-sending",
            title = "If SOS does not send",
            hazard = "",
            summary = "SOS is stored on your phone first and relays phone-to-phone — it does not need internet. If it has not gone out, work through these steps and call 112 if it is urgent.",
            sections = listOf(
                s(
                    "What it is",
                    "Your SOS is saved on this phone the moment you send it, and then relays from phone to phone until a phone with internet (a gateway) carries it to the server.",
                    "No internet on your phone is normal — the mesh is built for that. SOS never needs a login."
                ),
                s(
                    "Before",
                    "Keep the relay switched on (Mesh tab) and Bluetooth and Wi-Fi on, so your phone can find nearby phones.",
                    "Keep your phone charged and carry it with you.",
                    "Know your meeting point and your out-of-area contact (see the Family plan)."
                ),
                s(
                    "During",
                    "Wait a minute: the record first goes to nearby phones, then to a gateway. Check the record's status line — it tells you how far it got.",
                    "Move closer to other people: the mesh needs a nearby phone to carry your SOS onward.",
                    "If another phone near you has internet, ask them to open the app — their phone can act as the gateway.",
                    "Check the Mesh tab: is the relay on, and are any peers visible? If not, switch the relay off and on again.",
                    "Do not delete the record — it stays queued on your phone and will sync when a gateway is found."
                ),
                s(
                    "After",
                    "Once a gateway is reached, the status changes to 'Reached the server'.",
                    "If the record is still unsent after a long time, keep the phone on and near other phones."
                ),
                s(
                    "Do NOT",
                    "Do not keep re-sending the same SOS — one record is enough, and duplicates crowd the mesh.",
                    "Do not switch off the relay or the phone while waiting.",
                    "Do not rely only on the app when someone is in immediate danger."
                ),
                s(
                    "When to send SOS",
                    "Send SOS for any serious danger, and keep trying the steps above — the record stays queued and will go out when a gateway is found.",
                    "If anyone is in immediate danger, call 112 at once — do not wait for the app.",
                    "Tell the dispatcher where you are, what happened, and how many people are affected. Stay on the line."
                )
            ),
            source = "RescueMesh app flow (mesh relay + gateway sync); emergency number 112 (India)"
        )
    )

    fun byId(id: String): Guide? = ALL.firstOrNull { it.id == id }

    /**
     * Pure list filter for the Prepare list (unit-tested): [query]
     * matches title, summary, source or any bullet (case-insensitive,
     * multi-word queries match when every word appears somewhere);
     * [hazard] narrows to one chip ("all" = no narrowing); [savedOnly]
     * keeps only guides whose id is in [savedIds].
     */
    fun filter(
        guides: List<Guide> = ALL,
        query: String = "",
        hazard: String? = null,
        savedIds: Set<String> = emptySet(),
        savedOnly: Boolean = false
    ): List<Guide> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return guides.filter { guide ->
            if (savedOnly && guide.id !in savedIds) return@filter false
            if (hazard != null && hazard != "all" && guide.hazard != hazard) return@filter false
            if (words.isEmpty()) return@filter true
            val haystack = buildString {
                append(guide.title).append(' ')
                append(guide.summary).append(' ')
                append(guide.source).append(' ')
                guide.sections.forEach { section ->
                    append(section.title).append(' ')
                    section.bullets.forEach { append(it).append(' ') }
                }
            }.lowercase()
            words.all { it in haystack }
        }
    }

    /**
     * Plain-language status for one stored record (Prompt 10,
     * Emergency screen — surfacing only). The [LocalState] values
     * already exist; this only translates them into ordinary words.
     * No new state is introduced.
     */
    fun statusLineFor(localState: String): String = when (localState) {
        "CREATED", "QUEUED" -> "Sending to the mesh…"
        "SENDING" -> "Sending to a nearby phone…"
        "PEER_ACKED" -> "Reached phone-to-phone — waiting for a gateway"
        "SERVER_SYNCED" -> "Reached the server ✓"
        "FAILED_RETRYABLE" -> "Did not reach — will try again"
        "EXPIRED" -> "Did not reach — time expired"
        "REJECTED" -> "Rejected by the server"
        else -> "Saved on this phone"
    }
}
