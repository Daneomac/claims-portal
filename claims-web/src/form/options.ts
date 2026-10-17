import type { LossType, ProvinceCode } from '../api/claimsApi'

// Typed as Records so the compiler complains if the API gains a value without a label.
export const LOSS_TYPE_LABELS: Record<LossType, string> = {
  FIRE: 'Fire or smoke',
  WATER: 'Water damage',
  WIND_HAIL: 'Wind or hail',
  THEFT: 'Theft or break-in',
  VANDALISM: 'Vandalism',
  OTHER: 'Something else',
}

export const PROVINCE_NAMES: Record<ProvinceCode, string> = {
  AB: 'Alberta',
  BC: 'British Columbia',
  MB: 'Manitoba',
  NB: 'New Brunswick',
  NL: 'Newfoundland and Labrador',
  NS: 'Nova Scotia',
  NT: 'Northwest Territories',
  NU: 'Nunavut',
  ON: 'Ontario',
  PE: 'Prince Edward Island',
  QC: 'Quebec',
  SK: 'Saskatchewan',
  YT: 'Yukon',
}

export const lossTypeOptions = Object.entries(LOSS_TYPE_LABELS) as [LossType, string][]

// Alphabetical by name, which is not the same order as the codes
export const provinceOptions = (Object.entries(PROVINCE_NAMES) as [ProvinceCode, string][]).sort(
  ([, a], [, b]) => a.localeCompare(b, 'en'),
)
