package dev.kotlinds.pokemonclient.games.platinum

/**
 * The developers' names of Platinum's maps, index = zone id (`MAP_HEADER_*` of the pokeplatinum decomp's
 * generated/map_headers.txt, prettified: "Twinleaf Town Player House 2F"). They are not in the ROM, which only has the
 * place names ([PlatinumWorldSource.locationName], shared by a town and its buildings): they tell the maps of one
 * place apart, like HeartGold / SoulSilver's `maps.txt` ([dev.kotlinds.pokemonclient.games.hgss.HgssData.internalMapName]).
 * Placeholders (`MAP_HEADER_UNKNOWN_*`) are empty lines. Regenerate from the decomp when it renames maps.
 */
internal object PlatinumMapNames {

    /** The developers' name of zone [id], null when unknown or a placeholder. */
    fun of(id: Int): String? = names.getOrNull(id)?.takeIf { it.isNotEmpty() }

    private val names: List<String> by lazy { TABLE.trimIndent().lines() }

    private val TABLE = """
Everywhere
Nothing
Underground
Jubilife City
Jubilife City Mart
Jubilife City Unknown House 1
Jubilife City Pokecenter 1F
Jubilife City Pokecenter 2F
Poketch Co 1F
Poketch Co 2F
Poketch Co 3F
Jubilife Tv 1F
Jubilife Tv 2F
Jubilife Tv 3F
Jubilife Tv 4F
Jubilife Tv 2F Gallery
Jubilife Tv 3F Global Ranking Room
Jubilife Tv 3F Group Ranking Room
Jubilife Tv Elevator
Jubilife City South House 1F
Jubilife City South House 2F
Jubilife City South House 3F
Jubilife City South House 4F
Jubilife City Unknown House 2
Jubilife City Condominiums 1F
Jubilife City Condominiums 2F
Jubilife City Condominiums 3F
Jubilife City Condominiums 4F
Global Terminal 1F
Trainers School
Jubilife City Southwest House 1F
Jubilife City Unknown House 3
Jubilife City Unknown House 4
Canalave City
Canalave City Mart
Canalave City Gym
Canalave City Pokecenter 1F
Canalave City Pokecenter 2F
Canalave Library 1F
Canalave Library 2F
Canalave Library 3F
Canalave City Southeast House
Canalave City East House
Canalave City Harbor Inn
Canalave City Sailor Eldritch House
Oreburgh City
Oreburgh City Mart
Oreburgh City Gym
Oreburgh City Pokecenter 1F
Oreburgh City Pokecenter 2F
Oreburgh City Northwest House 1F
Oreburgh City Northwest House 2F
Oreburgh City Northwest House 3F
Oreburgh City Northwest House 4F
Oreburgh City North House 1F
Oreburgh City North House 2F
Oreburgh City North House 3F
Oreburgh City North House 4F
Oreburgh City Middle House
Mining Museum
Oreburgh City West House
Oreburgh City East House 1F
Oreburgh City East House 2F
Oreburgh City East House 3F
Oreburgh City South House
Eterna City
Eterna City Mart
Eterna City Gym
Eterna City Dp Gym
Eterna City Pokecenter 1F
Eterna City Pokecenter 2F
Cycle Shop
Team Galactic Eterna Building 1F
Team Galactic Eterna Building 2F
Team Galactic Eterna Building 3F
Team Galactic Eterna Building 4F
Eterna City Condominiums 1F
Eterna City Condominiums 2F
Eterna City Condominiums 3F
Unused Eterna City Condominiums 4F
Route 206 Cycling Road North Gate
Eterna City Herb Shop
Eterna City South House
Eterna City East House
Eterna City Underground Man House
Eterna City Unknown House
Hearthome City
Hearthome City Mart
Hearthome City Gym Entrance Room
Hearthome City Gym Trainer Room 1
Hearthome City Gym Trainer Room 2
Hearthome City Gym Leader Room
Hearthome City Dp Gym Trainer Room 1
Hearthome City Dp Gym Elevator Room 1
Hearthome City Dp Gym Trainer Room 2
Hearthome City Dp Gym Elevator Room 2
Hearthome City Dp Gym Trainer Room 3
Hearthome City Dp Gym Trainer Room 4
Hearthome City Dp Gym Trainer Room 5
Hearthome City Dp Gym Trainer Room 6
Hearthome City Dp Gym Leader Room
Hearthome City Pokecenter 1F
Hearthome City Pokecenter 2F
Hearthome City Southeast House 1F
Hearthome City Southeast House 2F
Hearthome City Southeast House Elevator
Hearthome City Pokemon Fan Club
Hearthome City West Gate To Amity Square
Hearthome City East Gate To Amity Square
Route 208 Gate To Hearthome City
Route 209 Gate To Hearthome City
Route 212 Gate To Hearthome City
Hearthome City Northeast House 1F
Hearthome City Northeast House 2F
Hearthome City Northeast House Elevator
Hearthome City Northwest House
Poffin House
Contest Hall Lobby
Contest Hall Stage Ongoing Contest
Foreign Building
Pastoria City
Pastoria City Mart
Pastoria City Gym
Pastoria City Pokecenter 1F
Pastoria City Pokecenter 2F
Pastoria City Observatory Gate 1F
Pastoria City Observatory Gate 2F
Pastoria City Southwest House
Pastoria City Middle House
Pastoria City East House
Pastoria City North House
Pastoria City Northeast House
Veilstone City
Veilstone City Gym
Veilstone City Pokecenter 1F
Veilstone City Pokecenter 2F
Game Corner
Veilstone Store 1F
Veilstone Store 2F
Veilstone Store 3F
Veilstone Store 4F
Veilstone Store 5F
Veilstone Store Elevator
Veilstone City Galactic Warehouse
Veilstone City Prize Exchange
Veilstone City Southeast House
Veilstone City Northwest House
Veilstone City Northeast House
Veilstone City Southwest House
Route 215 Gate To Veilstone City
Sunyshore City
Sunyshore City Pokecenter 1F
Sunyshore City Pokecenter 2F
Sunyshore City Mart
Sunyshore City Gym Room 1
Sunyshore City Gym Room 2
Sunyshore City Gym Room 3
Sunyshore Market
Sunyshore City Northeast House
Sunyshore City West House
Sunyshore City Northwest House
Sunyshore City Unknown House 1
Sunyshore City Unknown House 2
Sunyshore City East House
Vista Lighthouse
Snowpoint City
Snowpoint City Mart
Snowpoint City Gym
Snowpoint City Pokecenter 1F
Snowpoint City Pokecenter 2F
Snowpoint City West House
Snowpoint City East House
Pokemon League
Pokemon League South Pokecenter 1F
Pokemon League South Pokecenter 2F
Pokemon League North Pokecenter 1F
Pokemon League Elevator To Aaron Room
Pokemon League Aaron Room
Pokemon League Elevator To Bertha Room
Pokemon League Bertha Room
Pokemon League Elevator To Flint Room
Pokemon League Flint Room
Pokemon League Elevator To Lucian Room
Pokemon League Lucian Room
Pokemon League Elevator To Champion Room
Pokemon League Champion Room
Pokemon League Hallway To Hall Of Fame
Pokemon League Hall Of Fame
Fight Area
Fight Area Pokecenter 1F
Fight Area Pokecenter 2F
Fight Area Mart
Battle Park Gate To Fight Area
Route 225 Gate To Fight Area
Fight Area Middle House
Fight Area South House
Fight Area Unknown House

Oreburgh Mine B1F
Oreburgh Mine B2F
Valley Windworks Outside
Valley Windworks Building
Eterna Forest Outside
Eterna Forest
Fuego Ironworks Outside
Fuego Ironworks Building

Mt Coronet 1F South
Mt Coronet 2F
Mt Coronet 3F
Mt Coronet Outside North
Mt Coronet Outside South
Mt Coronet 4F Rooms 1 And 2
Mt Coronet 4F Room 3
Mt Coronet 5F
Mt Coronet 6F
Mt Coronet 1F Tunnel Room
Mt Coronet 1F North Room 2
Mt Coronet 1F North Room 1
Mt Coronet B1F
Spear Pillar
Spear Pillar Distorted

Pastoria City Dp Great Marsh

Solaceon Ruins Maniac Tunnel Room
Solaceon Ruins Room 1
Solaceon Ruins Room 2 Northeast Dead End
Solaceon Ruins Room 1 Northwest Dead End
Solaceon Ruins Room 2
Solaceon Ruins Room 1 Southeast Dead End
Solaceon Ruins Room 3
Solaceon Ruins Room 2 Southeast Dead End
Solaceon Ruins Room 6 Southeast Dead End
Solaceon Ruins Room 5 Southwest Dead End
Solaceon Ruins Room 3 Northwest Dead End
Solaceon Ruins Room 3 Southwest Dead End
Solaceon Ruins Room 4
Solaceon Ruins Room 6
Solaceon Ruins Room 5
Solaceon Ruins Room 7
Solaceon Ruins Room 4 Southeast Dead End
Solaceon Ruins Room 6 Northwest Dead End

Victory Road 1F
Victory Road 2F
Victory Road B1F
Victory Road 1F Room 2
Victory Road 1F Room 1
Victory Road 1F Room 3

Pal Park

Amity Square
Ravaged Path

Floaroma Meadow
Floaroma Meadow House
Oreburgh Gate 1F
Oreburgh Gate B1F
Fullmoon Island
Fullmoon Island Forest
Stark Mountain Outside
Stark Mountain Room 1
Stark Mountain Room 2
Stark Mountain Room 3

Sendoff Spring
Turnback Cave Entrance
Turnback Cave Pillar Room
Turnback Cave Giratina Room
Turnback Cave Pillar 1 Room 1
Turnback Cave Pillar 1 Room 2
Turnback Cave Pillar 1 Room 3
Flower Paradise



Snowpoint Temple 1F
Snowpoint Temple B1F
Snowpoint Temple B2F
Snowpoint Temple B3F
Snowpoint Temple B4F
Snowpoint Temple B5F
Wayward Cave 1F
Wayward Cave B1F
Ruin Maniac Cave Short
Trophy Garden
Iron Island
Iron Island 1F
Iron Island B1F Left Room
Iron Island B1F Right Room
Iron Island B2F Right Room
Iron Island B2F Left Room
Iron Island B3F
Old Chateau
Old Chateau Dining Area
Old Chateau Side Rooms
Old Chateau Corridor
Old Chateau Back West Room
Old Chateau Back Middle West Room
Old Chateau Back Middle Room
Old Chateau Back Middle East Room
Old Chateau Back East Room

Galactic Hq 1F
Galactic Hq 2F
Galactic Hq 3F
Galactic Hq 4F
Galactic Hq B1F
Galactic Hq B2F
Lake Verity Low Water
Lake Verity
Verity Cavern
Lake Valor Drained
Lake Valor
Valor Cavern
Lake Acuity Low Water
Lake Acuity
Acuity Cavern
Newmoon Island
Newmoon Island Forest
Battle Park
Battle Park Exchange Service Corner


Battle Tower
Battle Tower Elevator
Battle Tower Corridor
Battle Tower Corridor Multi
Battle Tower Battle Room
Battle Tower Multi Battle Room
Communication Club Colosseum 2p
Communication Club Colosseum 4p
Verity Lakefront
Verity Lakefront Unknown House
Valor Lakefront
Restaurant
Grand Lake Valor Lakefront East House
Grand Lake Valor Lakefront West House
Acuity Lakefront
Spring Path
Route 201
Route 202
Route 203
Route 204 South
Route 204 North
Route 205 South
Route 205 House
Route 205 North
Route 206
Route 206 Cycling Road South Gate
Gate Between Eterna City Route 206
Route 207
Route 208
Route 208 House
Route 209
Route 209 Lost Tower 1F
Route 209 Lost Tower 2F
Route 209 Lost Tower 3F
Route 209 Lost Tower 4F
Route 209 Lost Tower 5F
Route 210 South
Route 210 North
Route 210 Grandma Wilma House
Route 211 West
Route 211 East
Route 212 North
Pokemon Mansion
Pokemon Mansion Maids Room
Pokemon Mansion Office
Route 212 South
Route 212 House
Route 213
Route 213 Gate To Pastoria City
Footstep House
Grand Lake Route 213 Lobby
Grand Lake Route 213 East House
Grand Lake Route 213 Northwest House
Grand Lake Route 213 Northeast House
Route 214
Route 214 Gate To Veilstone City
Route 215
Route 216
Route 216 House
Route 217
Route 217 West House
Route 217 Northeast House
Route 218
Route 218 Gate To Jubilife City
Route 218 Gate To Canalave City
Route 219
Route 221
Pal Park Lobby
Route 221 House
Route 222
Route 222 West House
Route 222 East House
Route 222 Gate To Sunyshore City
Route 224
Route 225


Route 227


Route 228
Route 229


Record Mixing Room
Twinleaf Town
Twinleaf Town Rival House 1F
Twinleaf Town Rival House 2F
Twinleaf Town Player House 1F
Twinleaf Town Player House 2F
Twinleaf Town Northeast House
Twinleaf Town Southwest House
Sandgem Town
Sandgem Town Mart
Sandgem Town Pokecenter 1F
Sandgem Town Pokecenter 2F
Sandgem Town Pokemon Research Lab
Sandgem Town Counterpart House 1F
Sandgem Town Counterpart House 2F
Sandgem Town House
Floaroma Town
Floaroma Town Mart
Floaroma Town Pokecenter 1F
Floaroma Town Pokecenter 2F
Flower Shop
Floaroma Town Southeast House
Floaroma Town Middle House
Solaceon Town
Solaceon Town Mart
Solaceon Town Pokecenter 1F
Solaceon Town Pokecenter 2F
Pokemon Day Care
Solaceon Town Northeast House
Solaceon Town Pokemon News Press
Solaceon Town North House
Solaceon Town East House
Celestic Town
Celestic Town Pokecenter 1F
Celestic Town Pokecenter 2F
Celestic Town North House
Celestic Town Northwest House
Celestic Town Northeast House
Celestic Town Southwest House
Celestic Town Cave
Survival Area
Survival Area Mart
Survival Area Pokecenter 1F
Survival Area Pokecenter 2F
Battleground
Survival Area South House
Survival Area North House
Resort Area
Resort Area Mart
Resort Area Pokecenter 1F
Resort Area Pokecenter 2F
Resort Area Ribbon Syndicate 1F
Resort Area Ribbon Syndicate 2F
Resort Area Ribbon Syndicate Elevator
Villa
Resort Area House
Union Room
Route 220
Route 223
Route 226

Route 230
Seabreak Path

Jubilife City Pokecenter B1F
Canalave City Pokecenter B1F
Oreburgh City Pokecenter B1F
Eterna City Pokecenter B1F
Hearthome City Pokecenter B1F
Pastoria City Pokecenter B1F
Veilstone City Pokecenter B1F
Sunyshore City Pokecenter B1F
Snowpoint City Pokecenter B1F
Pokemon League South Pokecenter B1F
Fight Area Pokecenter B1F
Sandgem Town Pokecenter B1F
Floaroma Town Pokecenter B1F
Solaceon Town Pokecenter B1F
Celestic Town Pokecenter B1F
Survival Area Pokecenter B1F
Resort Area Pokecenter B1F
Canalave City West House
Cafe
Battle Tower Battle Salon
Galactic Hq Control Room
Pokemon League North Pokecenter 2F
Pokemon League North Pokecenter B1F
Galactic Hq Laboratory
Route 225 House
Route 226 House
Route 227 House
Route 228 Gate To Route 226
Route 228 North House
Route 228 South House
Great Marsh 1
Great Marsh 2
Great Marsh 3
Great Marsh 4
Great Marsh 5
Great Marsh 6
Hall Of Origin

Ruin Maniac Cave Long
Maniac Tunnel
Iron Island House
Solaceon Ruins Room 5 Southeast Deadend
Vista Lighthouse Elevator
Jubilife City Southwest House 2F
Turnback Cave Pillar 1 Room 4
Turnback Cave Pillar 1 Room 5
Turnback Cave Pillar 1 Room 6
Turnback Cave Pillar 2 Room 1
Turnback Cave Pillar 2 Room 2
Turnback Cave Pillar 2 Room 3
Turnback Cave Pillar 2 Room 4
Turnback Cave Pillar 2 Room 5
Turnback Cave Pillar 2 Room 6
Turnback Cave Pillar 3 Room 1
Turnback Cave Pillar 3 Room 2
Turnback Cave Pillar 3 Room 3
Turnback Cave Pillar 3 Room 4
Turnback Cave Pillar 3 Room 5
Turnback Cave Pillar 3 Room 6

























Contest Hall Stage No Contest
Battle Frontier
Battle Frontier Gate To Fight Area

Battle Factory
Battle Hall
Battle Castle
Battle Arcade
Veilstone Store B1F
Global Terminal 2F
Global Terminal 3F
Galactic Hq Hall

Rotoms Room

Distortion World 1F
Distortion World B1F
Distortion World B2F
Distortion World B3F
Distortion World B4F

Distortion World B5F
Distortion World B6F
Distortion World B7F
Distortion World Giratina Room
Distortion World Turnback Cave Room
Spear Pillar Dialga
Spear Pillar Palkia
Wifi Plaza Entrance
Iron Island Iron Ruins
Iron Ruins
Mt Coronet Iceberg Ruins
Iceberg Ruins
Route 228 Rock Peak Ruins
Rock Peak Ruins
"""
}
