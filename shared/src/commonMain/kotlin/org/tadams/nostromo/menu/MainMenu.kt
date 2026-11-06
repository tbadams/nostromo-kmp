package org.tadams.nostromo.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.tadams.nostromo.ui.Amber
import org.tadams.nostromo.ui.Background
import org.tadams.nostromo.ui.DimGreen
import org.tadams.nostromo.ui.Green

/** The first screen: start a game, or read how to play. */
@Composable
fun MainMenu(onStart: () -> Unit, onInstructions: () -> Unit) {
    Screen {
        Column(
            Modifier.align(Alignment.Center).width(320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Line("N O S T R O M O", Amber, bold = true)
            Spacer(Modifier.height(24.dp))
            MenuButton("START GAME", onStart, Amber)
            MenuButton("INSTRUCTIONS", onInstructions)
        }
    }
}

/** How to play, a page at a time, with Back and Next along the bottom. */
@Composable
fun InstructionsScreen(onBack: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val current = PAGES[page]
    Screen {
        Column(Modifier.align(Alignment.Center).widthIn(max = 720.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Line("INSTRUCTIONS", Amber, bold = true)
                Spacer(Modifier.weight(1f))
                Line("${page + 1} / ${PAGES.size}", DimGreen)
            }
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth().border(1.dp, DimGreen).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Line(current.title.uppercase(), Amber, bold = true)
                Spacer(Modifier.height(4.dp))
                current.lines.forEach { line ->
                    when {
                        line.isEmpty() -> Spacer(Modifier.height(4.dp))
                        line.startsWith("# ") -> Line(line.removePrefix("# "), Amber)
                        else -> Line(line, Green)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Back leaves from the first page; Next becomes Done on the last.
                MenuButton(if (page == 0) "MAIN MENU" else "BACK", { if (page == 0) onBack() else page-- }, Modifier.weight(1f))
                MenuButton(if (page == PAGES.lastIndex) "DONE" else "NEXT", { if (page == PAGES.lastIndex) onBack() else page++ }, Modifier.weight(1f), Amber)
            }
        }
    }
}

/** One page of the instructions. Lines starting "# " are subheadings; empty lines are gaps. */
private class Page(val title: String, val lines: List<String>)

// TODO move to string resources with the post-hackathon localization work (docs/architecture.md).
private val PAGES = listOf(
    Page("Overview", listOf(
        "Something is loose aboard the ship. It came out of one of the crew, and it is hiding in the air ducts.",
        "You command the remaining crew members. Hunt the creature down, or get off the ship alive.",
        "",
        "The game runs in real time. Switch between crew to give them orders, the creature will be acting in the meantime.",
        "",
        "Trust nobody completely. One of the crew may not be what they seem.",
    )),
    Page("Winning and losing", listOf(
        "# Ways to win",
        "Kill the creature, in a fight or by blowing it out of an airlock.",
        "Or arm the self-destruct and escape in the shuttle. Everyone still in play must be aboard, including Spot, the ship cat.",
        "",
        "# Ways to lose",
        "The oxygen runs out.",
        "Every crew member is down, lost or in hypersleep.",
        "The ship blows up: the self-destruct goes off, or a room reaches 100% damage.",
        "",
        "# Competence Rating",
        "At the end you're scored out of 100 for the crew who survive and how healthy they are, the oxygen left, and, if you killed the creature, how many rooms are still undamaged.",
        "Allowing the creature to reach Earth rewards no score!",
    )),
    Page("Controlling the crew", listOf(
        "# Choosing who to command",
        "Click a crew tab along the bottom, or press 1 to 7. Tab and Shift+Tab, or [ and ], move through them.",
        "",
        "# Giving orders",
        "The panel on the right lists everything the selected crew member can do right now. Click one to do it.",
        "Most actions take time; a bar under their tab shows progress. A new order replaces the current one. Esc cancels it.",
        "",
        "# Moving",
        "On the map, cyan rooms are where the selected crew member can go next. Click one, or use its Move button.",
        "",
        "# Morale",
        "Frightened crew may refuse to fight or crawl into the ducts. At their lowest they panic: they drop everything and run.",
        "Company, good weapons and the cat help. Finding bodies of your friends does not.",
    )),
    Page("Items", listOf(
        "Each crew member can have one item in use and carry one more. ",
        "",
        "# Weapons",
        "Electric Prod, Spanner, Incinerator: light damage.",
        "Laser Pistol: heavier damage, 8 shots, but damages the room.",
        "Harpoon Gun: one shot, enough to kill. It also kills everyone else in the room and wrecks the room.",
        "Net: one throw, freezes the creature for a while. Also useful for cats.",
        "",
        "# Tools",
        "Fire Extinguisher: puts out fires. A blast may scare the creature off.",
        "Tracker: tells whoever is using it if anything is moving in the next rooms: crew, cat or otherwise.",
        "Cat Box: for catching the cat, which you need to escape.",
    )),
    Page("The ship", listOf(
        "# Decks and ducts",
        "The ship has three decks joined by ladders, plus the shuttle. A network of air ducts runs behind the rooms.",
        "Each room has a grille into its duct. Grilles must be removed before anyone can pass, and the creature can remove them too. Listen for the clang.",
        "",
        "# Damage and fire",
        "Fighting damages rooms. At 30% the engine rooms catch fire and key systems warn; at 70% the structure is failing; at 100% the ship explodes.",
        "A fire keeps damaging its room until someone puts it out.",
        "",
        "# Special rooms",
        "Command Center: arm or override the self-destruct.",
        "Corridor 6: blow or seal the airlocks. Anything inside an airlock when it's blown is lost.",
        "Cryo Vault: crew can go into hypersleep, leaving play safely. Sleeping crew do not consume oxygen.",
        "Shuttle: launch, if everyone alive is aboard.",
        "",
        "# Oxygen",
        "Oxygen runs down all the time, shown at the top of the screen. Every living crewmember increases the rate of oxygen depletion.",
    )),
)

@Composable
private fun Screen(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Background).safeContentPadding().padding(24.dp), content = content)
}

@Composable
private fun MenuButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), color: Color = Green) {
    Box(
        modifier
            .border(1.dp, color, RectangleShape)
            .background(color.copy(alpha = 0.12f), RectangleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Line(label, color, bold = true)
    }
}

@Composable
private fun MenuButton(label: String, onClick: () -> Unit, color: Color) = MenuButton(label, onClick, Modifier.fillMaxWidth(), color)

@Composable
private fun Line(text: String, color: Color, bold: Boolean = false) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium)
}
