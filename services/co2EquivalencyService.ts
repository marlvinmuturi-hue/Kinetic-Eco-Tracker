/**
 * CO2 Equivalency Service
 * Provides relatable comparisons for CO2 emissions and conservation.
 * Data from Impact CO₂ (https://impactco2.fr) - ADEME Base Carbone.
 * Source: https://github.com/incubateur-ademe/impactco2
 */

export interface CO2Equivalency {
  icon: string;
  label: string;
  value: number;
  unit: string;
  description: string;
  category: 'nature' | 'food' | 'travel' | 'energy';
}

/**
 * Calculate CO2 equivalencies for a given amount of CO2 (in kg)
 */
export function calculateCO2Equivalencies(co2Kg: number, isEmission: boolean = false): CO2Equivalency[] {
  const equivalencies: CO2Equivalency[] = [];

  // Nature comparisons
  const treeDaysAbsorption = co2Kg / 0.06; // A mature tree absorbs ~0.06 kg CO2 per day
  const treeYearsAbsorption = co2Kg / 21.7; // A mature tree absorbs ~21.7 kg CO2 per year
  equivalencies.push({
    icon: '🌳',
    label: 'Trees needed (daily)',
    value: treeDaysAbsorption,
    unit: treeDaysAbsorption === 1 ? 'tree' : 'trees',
    description: `${isEmission ? 'Requires' : 'Equivalent to'} ${treeDaysAbsorption.toFixed(1)} tree${treeDaysAbsorption === 1 ? '' : 's'} absorbing CO2 for one day`,
    category: 'nature',
  });

  if (treeYearsAbsorption >= 0.1) {
    equivalencies.push({
      icon: '🌲',
      label: 'Trees needed (yearly)',
      value: treeYearsAbsorption,
      unit: treeYearsAbsorption === 1 ? 'tree' : 'trees',
      description: `${isEmission ? 'Requires' : 'Equivalent to'} ${treeYearsAbsorption.toFixed(2)} tree${treeYearsAbsorption === 1 ? '' : 's'} absorbing CO2 for one year`,
      category: 'nature',
    });
  }

  // Food comparisons
  const beefMeals = co2Kg / 6.61; // Average beef meal produces ~6.61 kg CO2
  const cheeseburgers = co2Kg / 3.64; // Cheeseburger produces ~3.64 kg CO2
  const veganMeals = co2Kg / 0.46; // Vegan meal produces ~0.46 kg CO2

  if (beefMeals >= 0.1) {
    equivalencies.push({
      icon: '🥩',
      label: 'Beef meals',
      value: beefMeals,
      unit: beefMeals === 1 ? 'meal' : 'meals',
      description: `${isEmission ? 'Equivalent to' : 'Saves'} ${beefMeals.toFixed(1)} beef meal${beefMeals === 1 ? '' : 's'}`,
      category: 'food',
    });
  }

  if (cheeseburgers >= 0.5) {
    equivalencies.push({
      icon: '🍔',
      label: 'Cheeseburgers',
      value: cheeseburgers,
      unit: cheeseburgers === 1 ? 'burger' : 'burgers',
      description: `${isEmission ? 'Equivalent to' : 'Saves'} ${cheeseburgers.toFixed(1)} cheeseburger${cheeseburgers === 1 ? '' : 's'}`,
      category: 'food',
    });
  }

  // Travel comparisons
  const carKm = co2Kg / 0.192; // Car emits ~0.192 kg CO2 per km
  const flightKm = co2Kg / 0.255; // Flight emits ~0.255 kg CO2 per km
  const trainKm = co2Kg / 0.041; // Train emits ~0.041 kg CO2 per km

  if (carKm >= 1) {
    equivalencies.push({
      icon: '🚗',
      label: 'Car kilometers',
      value: carKm,
      unit: 'km',
      description: `${isEmission ? 'Same as driving' : 'Avoided driving'} ${carKm.toFixed(1)} km in a car`,
      category: 'travel',
    });
  }

  if (flightKm >= 1) {
    equivalencies.push({
      icon: '✈️',
      label: 'Flight kilometers',
      value: flightKm,
      unit: 'km',
      description: `${isEmission ? 'Same as flying' : 'Avoided flying'} ${flightKm.toFixed(1)} km`,
      category: 'travel',
    });
  }

  // Short flight comparison (if significant)
  const shortFlights = co2Kg / 90; // Short domestic flight ~90 kg CO2
  if (shortFlights >= 0.1) {
    equivalencies.push({
      icon: '🛫',
      label: 'Domestic flights',
      value: shortFlights,
      unit: shortFlights === 1 ? 'flight' : 'flights',
      description: `${isEmission ? 'Equivalent to' : 'Saves'} ${shortFlights.toFixed(2)} short domestic flight${shortFlights === 1 ? '' : 's'} (1 hour)`,
      category: 'travel',
    });
  }

  // Media/entertainment (Impact CO2 / ADEME style - https://impactco2.fr)
  const harryPotterMovies = co2Kg / 0.241; // Watching a Harry Potter movie ~0.241 kg CO2e (Impact CO2)
  if (harryPotterMovies >= 0.1) {
    equivalencies.push({
      icon: '🎬',
      label: 'Harry Potter movies',
      value: harryPotterMovies,
      unit: harryPotterMovies === 1 ? 'movie' : 'movies',
      description: isEmission
        ? `Equivalent to watching ${harryPotterMovies.toFixed(1)} Harry Potter movie${harryPotterMovies === 1 ? '' : 's'} (Impact CO₂)`
        : `Equivalent to the CO₂ of watching ${harryPotterMovies.toFixed(1)} Harry Potter movie${harryPotterMovies === 1 ? '' : 's'} (Impact CO₂)`,
      category: 'energy',
    });
  }

  const smartphoneCharges = co2Kg / 0.011; // ~0.011 kg CO2 per full smartphone charge (ADEME)
  if (smartphoneCharges >= 1) {
    equivalencies.push({
      icon: '📱',
      label: 'Smartphone charges',
      value: smartphoneCharges,
      unit: smartphoneCharges === 1 ? 'charge' : 'charges',
      description: `${isEmission ? 'Equivalent to' : 'Saves'} ${smartphoneCharges.toFixed(0)} full smartphone charge${smartphoneCharges === 1 ? '' : 's'}`,
      category: 'energy',
    });
  }

  // Energy comparisons
  const kwhElectricity = co2Kg / 0.475; // Average electricity grid emits ~0.475 kg CO2 per kWh
  const laptopHours = co2Kg / 0.02; // Laptop uses ~0.02 kg CO2 per hour
  const streamingHours = co2Kg / 0.055; // Video streaming emits ~0.055 kg CO2 per hour

  if (kwhElectricity >= 1) {
    equivalencies.push({
      icon: '⚡',
      label: 'Electricity (kWh)',
      value: kwhElectricity,
      unit: 'kWh',
      description: `${isEmission ? 'Equivalent to using' : 'Saves'} ${kwhElectricity.toFixed(1)} kWh of grid electricity`,
      category: 'energy',
    });
  }

  if (laptopHours >= 1) {
    equivalencies.push({
      icon: '💻',
      label: 'Laptop usage',
      value: laptopHours,
      unit: laptopHours === 1 ? 'hour' : 'hours',
      description: `${isEmission ? 'Same as' : 'Saves'} ${laptopHours.toFixed(0)} hour${laptopHours === 1 ? '' : 's'} of laptop use`,
      category: 'energy',
    });
  }

  if (streamingHours >= 1) {
    equivalencies.push({
      icon: '📺',
      label: 'Video streaming',
      value: streamingHours,
      unit: streamingHours === 1 ? 'hour' : 'hours',
      description: `${isEmission ? 'Same as' : 'Saves'} ${streamingHours.toFixed(1)} hour${streamingHours === 1 ? '' : 's'} of HD video streaming`,
      category: 'energy',
    });
  }

  // Household comparisons
  const householdDays = co2Kg / 21.4; // Average household emits ~21.4 kg CO2 per day
  if (householdDays >= 0.1) {
    equivalencies.push({
      icon: '🏠',
      label: 'Household energy',
      value: householdDays,
      unit: householdDays === 1 ? 'day' : 'days',
      description: `${isEmission ? 'Same as' : 'Saves'} ${householdDays.toFixed(1)} day${householdDays === 1 ? '' : 's'} of average household energy`,
      category: 'energy',
    });
  }

  return equivalencies;
}

/**
 * Get the most relatable equivalencies (top 5)
 */
export function getTopEquivalencies(co2Kg: number, isEmission: boolean = false): CO2Equivalency[] {
  const all = calculateCO2Equivalencies(co2Kg, isEmission);
  
  // Prioritize based on relevance (values between 0.5 and 100 are most relatable)
  const scored = all.map(eq => ({
    ...eq,
    score: eq.value >= 0.5 && eq.value <= 100 ? 100 : 
           eq.value < 0.5 ? eq.value * 200 : 
           100 / (eq.value / 100)
  }));
  
  // Sort by score and take top 5
  return scored
    .sort((a, b) => b.score - a.score)
    .slice(0, 5)
    .map(({ score, ...eq }) => eq);
}

/**
 * Get a single, most relatable equivalency
 */
export function getMostRelatableEquivalency(co2Kg: number, isEmission: boolean = false): CO2Equivalency | null {
  const top = getTopEquivalencies(co2Kg, isEmission);
  return top.length > 0 ? top[0] : null;
}

/** Impact CO₂ equivalents (kg CO2e per unit) - from impactco2.fr equivalents.csv */
const IMPACT_CO2_EQUIVALENTS: Array<{ name: string; kgCo2e: number; icon: string }> = [
  { name: 'Harry Potter marathon (streaming)', kgCo2e: 0.63, icon: '🎬' },
  { name: 'Game of Thrones episode (streaming)', kgCo2e: 0.0317, icon: '📺' },
  { name: '1 hour video streaming', kgCo2e: 0.064, icon: '📺' },
  { name: 'Traditional baguette', kgCo2e: 0.78, icon: '🥖' },
  { name: 'Coffee', kgCo2e: 0.64, icon: '☕' },
  { name: 'Tea', kgCo2e: 0.044, icon: '🍵' },
  { name: 'Vegan meal', kgCo2e: 0.39, icon: '🥗' },
  { name: 'Smartphone charger', kgCo2e: 0.42, icon: '📱' },
  { name: 'Paris–Marseille TGV (round trip)', kgCo2e: 4.4, icon: '🚄' },
  { name: 'Night camping', kgCo2e: 1.4, icon: '⛺' },
  { name: 'Night in a hotel', kgCo2e: 4.3, icon: '🏨' },
  { name: 'Friends series (full streaming)', kgCo2e: 7.86, icon: '📺' },
  { name: 'Cheeseburger', kgCo2e: 18.83, icon: '🍔' },
  { name: 'km by car (petrol)', kgCo2e: 0.218, icon: '🚗' },
  { name: 'Paris–New York flight (round trip)', kgCo2e: 2060, icon: '✈️' },
];

/**
 * Get top 3 equivalencies for net impact from Impact CO₂ data.
 * Positive net = conservation, negative net = emissions.
 */
export function getNetImpactEquivalencies(netImpactKg: number): CO2Equivalency[] {
  const absKg = Math.abs(netImpactKg);
  if (absKg < 0.001) return [];

  const isEmission = netImpactKg < 0;
  const prefix = isEmission ? 'Equivalent to' : 'Equivalent to the CO₂ of';

  const scored = IMPACT_CO2_EQUIVALENTS
    .filter((e) => absKg >= e.kgCo2e * 0.05) // Skip if result would be < 0.05 units
    .map((e) => {
      const value = absKg / e.kgCo2e;
      const desc =
        value < 0.1
          ? `${prefix} ${(value * 100).toFixed(0)}% of ${e.name}`
          : e.name.startsWith('km by')
            ? `${prefix} ${value.toFixed(1)} ${e.name}`
            : `${prefix} ${value.toFixed(1)} ${e.name}${value === 1 ? '' : 's'}`;
      // Prefer values between 0.2 and 20 (most relatable)
      const score = value >= 0.2 && value <= 20 ? 100 : value < 0.2 ? value * 300 : 80 / (value / 20);
      return {
        icon: e.icon,
        label: e.name,
        value,
        unit: value === 1 ? 'unit' : 'units',
        description: desc,
        category: 'energy' as const,
        score,
      };
    });

  return scored
    .sort((a, b) => b.score - a.score)
    .slice(0, 3)
    .map(({ score, ...eq }) => eq);
}

/**
 * Get a single equivalency for net impact (backward compatibility).
 */
export function getNetImpactEquivalency(netImpactKg: number): CO2Equivalency | null {
  const equivs = getNetImpactEquivalencies(netImpactKg);
  return equivs.length > 0 ? equivs[0] : null;
}

/**
 * Format CO2 value with appropriate unit
 */
export function formatCO2Value(co2Kg: number): string {
  if (co2Kg < 0.001) {
    return `${(co2Kg * 1000000).toFixed(0)} mg`;
  } else if (co2Kg < 1) {
    return `${(co2Kg * 1000).toFixed(0)} g`;
  } else if (co2Kg < 1000) {
    return `${co2Kg.toFixed(2)} kg`;
  } else {
    return `${(co2Kg / 1000).toFixed(2)} tonnes`;
  }
}
