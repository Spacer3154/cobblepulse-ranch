# CobblePulse Ranch

Pension et reproduction des Pokémon pour Cobble Pulse (Minecraft 1.21.1, Fabric, Cobblemon 1.8.4), d'après le cahier des charges « Mod CobblePulse-Ranch ». Le même jar va sur le serveur et dans le modpack (bloc et objets enregistrés des deux côtés). Inspiré de Cobbreeding, Daycare+ et Pixelmon : aucun code ni aucune texture repris (les sabliers sont les textures Pixelmon fournies par Spacer).

## Bloc de Pension
- Modèle 3D propre, dans l'esprit du bloc de Pâture de Cobblemon : socle, barrière, nid avec un œuf, pupitre à écran incliné tourné vers le joueur qui le pose.
- **Aucun ticker** : chaque emplacement garde `timestamp_start` et `duration_ms`. L'avancement est calculé seulement à l'ouverture, au dépôt ou au retrait, à l'utilisation d'un sablier, sur une commande ou sur une demande de rafraîchissement de l'écran.
- **Aucune entité** : les parents sont stockés en NBT Cobblemon dans la pension.
- Ni waterloggable ni inflammable. Insensible aux explosions, au Wither, au dragon et aux pistons ; l'eau et la lave ne le cassent pas. Seul son propriétaire (ou le staff) peut le casser, et tout ce qu'il contient lui est rendu. Limite par chunk et par dresseur réglable (aucune par défaut).
- Écran : 3 emplacements. Chacun montre les parents choisis **dans le PC** (ils quittent le PC pendant leur séjour), leurs objets tenus et leur effet, la progression avec le compte à rebours, les conditions 5x5 (détail au survol), les œufs à récupérer et les boutons Sablier d'Argent et d'Or. Il y a aussi un Guide : la mécanique, puis les blocs et leurs points par groupe d'œufs.
- Recette : œuf, 2 bottes de foin, bloc de Pâture de Cobblemon, lingot de fer.

## Habitat 5x5
25 colonnes autour du bloc, sur les couches Y et Y+1 seulement. Chaque bloc rapporte des points jusqu'à un plafond, donc une monoculture ne suffit pas : 24 sources d'eau ne donnent que 15 points sur 50. La durée d'un cycle va de 1 h 30 (0 point) à 20 min (50 points). Le scan n'a lieu que sur événement, au plus une fois toutes les 2 s, et retient le meilleur groupe d'œufs du couple (Métamorph prend celui de son partenaire). Barème dans `habitat_blocks.json` (identifiants de blocs ou tags `#...`) : les listes du CDC (Eau, Plante, Dragon), complétées pour les 15 groupes.

## Génétique
Données lues dans Cobblemon au démarrage : espèces officielles, de mods, de datapacks et Fakémons (CobblePulse-Ecology).
- Compatibilité : un groupe d'œufs commun et des sexes opposés, ou Métamorph (jamais avec le groupe Inconnu). Métamorph x Métamorph est interdit par défaut et peut être autorisé en direct par le staff. Un Pokémon castré ne se reproduit pas.
- Espèce : premier stade de la lignée de la mère (ou du parent non-Métamorph). Les bébés à encens ne naissent que si un parent tient l'encens, sinon c'est le stade suivant ; les 9 encens sont ajoutés par le mod (table réglable). Nidoran ♀/♂, Muciole/Lumivole et Manaphy → Phione sont gérés.
- **Formes régionales** : si les deux parents ont la même forme régionale, l'œuf l'a toujours. Si les formes ou les espèces diffèrent (Métamorph compris), la forme de la mère passe avec 30 % de chances. La Pierre Stase garantit la forme de son porteur. Les formes Méga, Gigamax et de combat ne passent jamais.
- IV : 3 hérités (5 avec un Nœud Destin), un objet Pouvoir garantit sa statistique (si les deux parents en tiennent, un des deux au hasard). Nature : la Pierre Stase la garantit (deux porteurs : 50/50).
- Talent : 30 % de chances d'avoir le talent exact de la mère, talent caché compris. Sans talent caché chez la mère, jamais de talent caché.
- Ball de la mère (Master Ball et Mémoire Ball deviennent une Poké Ball), capacités œuf connues d'un parent, Herbe Miroir (le porteur apprend les capacités œuf que connaît son partenaire).
- Chromatique : 1/8192, ×6 avec la méthode Masuda (Dresseurs d'Origine de langues différentes).
- Fertilité : **désactivée** (choix de Spacer, elle nuit à la reproduction compétitive) : aucune limite de pontes, aucune donnée écrite sur les Pokémon. Réactivable avec `enable_fertility_system` (5 pontes par Pokémon, suit le Pokémon).

## Œufs, sabliers, drops
- L'œuf est un objet. Toute sa génétique est figée à la ponte (aucun nouveau tirage au GTS ni à l'éclosion). Il éclot en marchant, avec ×2 si un Pokémon à Corps Ardent, Armumagma ou Turbine est dans l'équipe. L'infobulle donne l'espèce, la progression et le statut ; avec Maj, la fiche complète. L'œuf brille s'il est chromatique. Apparence selon les types (textures fournies par Spacer) : fond du type principal, taches du second type (du même type pour un mono-type), 324 combinaisons ; œuf spécial pour Manaphy ; œuf par défaut pour un type inconnu.
- Sablier d'Argent : -25 % du temps de ponte restant, ou des pas restants d'un œuf (mode réglable). Sablier d'Or : ponte ou éclosion immédiate. Sur un œuf, clic droit avec le sablier sur l'œuf dans l'inventaire (ou sablier en main, œuf en main secondaire) ; sur une Pension, bouton dans l'écran.
- Drops comme dans Pixelmon (`drops.json`) : Nœud Destin lâché par Chaglam et Chaffreux (10 %), Pierre Stase lâchée par Leuphorie (10 %), à la capture, à la victoire et à la mort. Racaillou, Gravalanch, Nodulithe, Géolithe et Gigalithe tiennent une Pierre Stase une fois sur 20 quand on les capture.
- **Castration** : `/castrer <emplacement>` puis `/castrer <emplacement> confirmer`, réservée au Dresseur d'Origine, définitive. Le Pokémon ne se reproduit plus et la mention « Castré » apparaît au GTS.

## GTS (CobblePulse Utils)
Utils lit deux points d'entrée du mod, sans dépendance de compilation : `cobblepulse:pokemon_lines` (« Castré » sur la fiche des Pokémon : GTS, STS, Échange Miracle ; la fertilité aussi si elle est activée) et `cobblepulse:item_lines` (fiche GTS complète d'un œuf : espèce, talent, chromatique, nature, Ball, IV, capacités œuf, pas restants).

## Sécurité des données
- Registre central : un fichier par pension dans `world/cobbleranch/data/ranches/`, écrit de façon atomique, plus une copie dans le NBT du bloc. Si le fichier est perdu, la copie du bloc le restaure.
- Une copie du bloc posée ailleurs (WorldEdit, structure) est vidée : pas de duplication. Une pension dont le bloc a disparu devient orpheline et son contenu reste récupérable par `/daycare`.
- Journal des transferts (`journal.dat`) : chaque dépôt, retrait, œuf récupéré ou éclosion y est écrit avant d'agir, avec une copie de ce qui est déplacé ; il est effacé une fois les deux côtés enregistrés. Après un arrêt brutal, ce qui se trouve des deux côtés est retiré de la destination, et ce qui ne se trouve nulle part est remis à l'origine.
- Registre des éclosions : un œuf dupliqué ne peut pas éclore deux fois.
- Boîte de réception pour un dresseur hors ligne ou au PC plein, remise à la connexion.

## Commandes
| Commande | Rôle |
|---|---|
| `/daycare` | Écran de toutes ses Pensions ; ouverture à distance (VIP) |
| `/breed <empl.> <empl.>` | Analyse de compatibilité de deux Pokémon de l'équipe (chat) |
| `/breed <empl.> <empl.> lancer` | VIP : reproduction à distance, sans bloc |
| `/hatch` | Diagnostic de l'œuf tenu en main (chat) |
| `/hatch instant` | VIP : éclosion immédiate de l'œuf en main |
| `/eggsee` | Fiche complète de l'œuf en main (chat) |
| `/castrer <empl.> [confirmer]` | Castration (Dresseur d'Origine) ; alias `/neuter` |
| `/cobbleranch guide` | Guide de la Pension |
| `/cobbleranch admin` | Panneau d'administration |
| `/cobbleranch reload` | Recharge `config/cobbleranch/` |
| `/cobbleranch give <joueur> <objet> [n]` | Bloc, sabliers, encens |
| `/cobbleranch fertilite\|castration <joueur> <empl.> …` | Support staff |

## Permissions
Nœuds du CDC (section 7.2) et `cobbleranch.user.neuter`. Sans valeur dans LuckPerms : `user.*` et les commandes de base sont permis à tous ; les nœuds VIP, `bypass.*` et `admin.*` sont réservés aux opérateurs (niveau `op_level_fallback`, 2 par défaut). Un refus explicite dans LuckPerms l'emporte. Dans le panneau admin, un bouton sans permission n'est pas affiché ; une action forcée sans permission est refusée, enregistrée dans le journal et signalée au staff en ligne.

## Configuration (`config/cobbleranch/`)
- `config.json` : clés du CDC, plus `max_ranch_blocks_per_player`, `max_eggs_per_slot`, `language`, `masuda_mode`, `regional_form_mother_chance` (0,30), `inheritable_form_keywords`, `non_inheritable_balls`, `blocked_species`/`blocked_labels`, `incense_babies`, `offspring_variants`, `steps_per_egg_cycle` (256), `steps_per_block`, `silver_hourglass_fixed_egg_steps`, `hatch_boost_abilities`, `remote_breed` (emplacements, durée, coût), `neutering`.
- `habitat_blocks.json`, `drops.json`.

## Tests automatiques (GitHub Actions)
- Vrai serveur (Cobblemon 1.8.4, LuckPerms, Mega Showdown, CobblePulse-Ecology, datapack d'espèces de test) : contenu, règles et génétique sur des centaines de tirages, habitat dans le monde, évaluation à la demande, persistance, anti-duplication, journal.
- Arrêt brutal (`kill -9`) au milieu d'un dépôt, puis redémarrage : vérification qu'il n'y a pas de doublon.
- Vrai client connecté : bloc, écrans, choix dans le PC, sabliers, œuf, `/eggsee`, `/hatch`, éclosion, `/breed`, `/daycare`, castration, Guide, admin.

Les résultats et le jar sont publiés sur la branche `ci-output`.
