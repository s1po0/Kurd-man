/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

export const STRINGS = {
  gameTitle: 'Iron & Crown',
  subtitle: 'Realm of Ash and Gold',
  tagline: 'Forge your banner. Gather your host. Claim the fractured throne.',
  startNewGame: 'New Campaign',
  loadGame: 'Resume Campaign',
  settings: 'Options',
  credits: 'Chronicles',
  controlHint: 'Tap to Move · Double Tap Fast Travel · Drag to Pan Map · Pinch to Zoom',
  
  // Navigation / Tabs
  tabParty: 'Party',
  tabInventory: 'Inventory',
  tabCharacter: 'Hero Sheet',
  tabCamp: 'Rest & Wait',
  tabJournal: 'Quests',
  tabMenu: 'Menu',
  
  // Time controls
  pause: 'Pause',
  normalSpeed: '1x Speed',
  fastSpeed: '2x Speed',
  ultraSpeed: '4x Speed',
  
  // Resources
  gold: 'Gold Dinars',
  food: 'Food Supply',
  morale: 'Host Morale',
  troops: 'Warriors',
  day: 'Day',
  speed: 'Speed',
  weight: 'Burden',
  
  // Character creation steps
  creation: {
    title: 'Origins of the Wanderer',
    stepCulture: '1. Select Your Heritage',
    stepOrigin: '2. Upbringing & Lineage',
    stepYouth: '3. Youthful Calling',
    stepMotivation: '4. The Call to Arms',
    stepCustomization: '5. Name & Banner Heraldry',
    next: 'Proceed',
    back: 'Return',
    finish: 'Embark Upon the Realm',
    namePrompt: 'Your Chosen Name',
    defaultHeroName: 'Roland of the Marches',
    attributePointsLeft: 'Attribute Points Available',
    skillPointsLeft: 'Skill Focus Points Available',
  },

  // Encounters
  encounter: {
    spottedParty: 'You have intercepted a host upon the road:',
    relativeStrength: 'Relative Military Strength',
    actionAttack: 'Order the Charge (Engage in Battle)',
    actionTalk: 'Approach under Parley (Talk)',
    actionBribe: 'Offer Safe-Passage Toll (Bribe)',
    actionFlee: 'Sound the Retreat (Flee into the Wild)',
    bribeCost: 'Dinars Required:',
    fleeWarning: 'A hasty retreat may abandon luggage or exhaust your vanguard.',
    cannotFlee: 'You are cornered; battle is inevitable!',
  },

  // Settlement Hub
  settlement: {
    townGreeting: 'The bustling gates of {name} stand open. Guards keep watch from stone battlements.',
    villageGreeting: 'Smoke rises from thatched hearths in the village of {name}. Peasants labor in the fields.',
    castleGreeting: 'The towering ramparts of {name} overlook the high valleys. Armed guards stand at the portcullis.',
    visitMarket: 'Marketplace (Trade & Equipment)',
    visitTavern: 'Tavern (Companions & Rumors)',
    recruitTroops: 'Barracks / Elders (Recruit Volunteers)',
    visitArena: 'Tournament Arena (Martial Glory)',
    visitKeep: 'Great Keep (Audience with Nobility)',
    restWait: 'Rest until Dawn (Recover Wounded)',
    leave: 'Depart Gates to World Map',
    noRecruits: 'There are currently no able-bodied volunteers willing to take the oath here.',
    recruitedSuccess: 'Recruited {count} warriors into your host for {cost} gold!',
    restComplete: 'Your party rested peacefully. Wounded warriors have regained vitality.',
  },

  // Trade / Market
  market: {
    title: 'Grand Bazaar & Merchant Stalls',
    buy: 'Purchase',
    sell: 'Sell Goods',
    merchantGold: 'Merchant Wealth',
    yourGold: 'Your Purse',
    capacity: 'Luggage Burden',
    profitHintGood: 'High regional demand! Lucrative profit margin.',
    profitHintBad: 'Abundant local supply; selling here yields modest coin.',
    tradeSuccess: 'Transaction completed.',
  },

  // Party Screen
  party: {
    title: 'The Warband',
    maxSize: 'Warband Limit',
    dailyWages: 'Daily Wages Demand',
    foodConsumption: 'Daily Provisions Required',
    upgradeAvailable: 'Eligible for Veteran Promotion',
    disband: 'Disband Warriors',
    upgradeTo: 'Promote to {name}',
    upgradeCost: 'Promotion Cost: {cost} Gold',
    prisonersTitle: 'Captives & Hostages',
    ransomAll: 'Ransom All Captives ({gold} Gold)',
    recruitPrisoner: 'Coerce Captive into Service',
    wounded: 'Wounded in combat',
  },

  // Inventory Screen
  inventory: {
    title: 'Arms, Steeds & Luggage',
    weightCapacity: 'Pack Animal Capacity',
    slots: {
      head: 'Helm',
      body: 'Hauberk',
      hands: 'Gauntlets',
      feet: 'Boots',
      weapon1: 'Primary Weapon',
      weapon2: 'Secondary Weapon',
      shieldOrArrows: 'Shield / Quiver',
      mount: 'Warhorse',
    },
    equip: 'Equip',
    unequip: 'Unequip',
    drop: 'Discard Item',
    statsDamage: 'Strike Force',
    statsArmor: 'Armor Protection',
    statsWeight: 'Weight',
    statsValue: 'Market Value',
    statsFoodDays: 'Provisions for Host',
  },

  // Skirmish Battle
  battle: {
    title: 'The Field of Strife',
    victory: 'Decisive Victory!',
    defeat: 'Rout and Defeat!',
    skirmishSummary: 'Casualty Ledger and Martial Spoils',
    playerCasualties: 'Friendly Casualties',
    enemyCasualties: 'Hostile Casualties',
    killed: 'Slain',
    wounded: 'Wounded',
    goldPlundered: 'War Spoils Looted',
    xpAwarded: 'Warband Experience Earned',
    prisonersCaptured: 'Prisoners Shackled',
    itemsLooted: 'Luggage Recovered',
    proceed: 'Collect Spoils & Regroup',
  },

  // Save / Load
  saveLoad: {
    title: 'Campaign Chronicles',
    autosaveSlot: 'Autosave Slot',
    slot1: 'Chronicle Slot I',
    slot2: 'Chronicle Slot II',
    slot3: 'Chronicle Slot III',
    emptySlot: 'Empty Parchment',
    saveButton: 'Record Chronicle (Save)',
    loadButton: 'Resume (Load)',
    deleteButton: 'Wipe Record',
    saveSuccess: 'Campaign successfully etched into memory!',
    loadSuccess: 'Chronicle loaded. The realm awaits.',
  },

  // Settings
  settingsStrings: {
    title: 'Imperial Settings & Display',
    audioMute: 'Silence All Audio',
    masterVolume: 'Master Volume',
    sfxVolume: 'Battle & FX Volume',
    musicVolume: 'Ambient Tone Volume',
    dprCap: 'High Definition Rendering (DPR 2.0)',
    fogOfWar: 'Shroud of Unknown Lands (Fog of War)',
    difficulty: 'Campaign Difficulty',
    close: 'Save & Return',
  },

  // Interactive Tutorial
  tutorial: {
    title: 'The Mercenary’s Primer',
    step1: 'Welcome to the continent of Caladria. Tap any spot on the terrain to order your warband to march there.',
    step2: 'Visit nearby settlements (Towns, Villages) to recruit volunteers, buy grain and dried fish, or trade goods for profit.',
    step3: 'Keep an eye on hostile raiders and deserters. Intercepting or being caught will trigger tactical encounters.',
    step4: 'Manage your warband through the Party screen. As troops survive skirmishes, they earn XP to be promoted into elite veterans.',
    step5: 'Use the time buttons on the top right (Pause, 1x, 2x, 4x) to manage campaign pacing. Time automatically freezes when you stop.',
    understood: 'I am ready to conquer!',
  },
};
