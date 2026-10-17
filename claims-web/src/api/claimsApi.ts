// The claims-api contract (POST /api/v1/claims). Field names and enum values mirror the
// Java request records in claims-api, so a server error's `field` path (for example
// "loss.date") is also the name of the matching form field.

export const LOSS_TYPES = ['FIRE', 'WATER', 'WIND_HAIL', 'THEFT', 'VANDALISM', 'OTHER'] as const
export type LossType = (typeof LOSS_TYPES)[number]

export const PROVINCE_CODES = [
  'AB',
  'BC',
  'MB',
  'NB',
  'NL',
  'NS',
  'NT',
  'NU',
  'ON',
  'PE',
  'QC',
  'SK',
  'YT',
] as const
export type ProvinceCode = (typeof PROVINCE_CODES)[number]

export interface SubmitClaimRequest {
  policyholder: {
    firstName: string
    lastName: string
    email: string
    phone?: string
  }
  policyNumber: string
  loss: {
    date: string // YYYY-MM-DD
    type: LossType
    description: string
    estimatedAmount?: number
  }
  propertyAddress: {
    street: string
    unit?: string
    city: string
    province: ProvinceCode
    postalCode: string
  }
  declarationAccepted: boolean
}

/** The 201 Created body. */
export interface SubmittedClaim {
  claimNumber: string
  status: string
  submittedAt: string
}

/** One entry of a 400 response's `errors` array. */
export interface ApiFieldError {
  field: string
  message: string
}

export type SubmitClaimResult =
  | { kind: 'success'; claim: SubmittedClaim }
  | { kind: 'field-errors'; errors: ApiFieldError[] }
  // Network error, 5xx, rate limiting, or a response we can't make sense of: nothing the
  // customer can fix by editing a field, so the UI offers a retry.
  | { kind: 'failure' }

const CLAIMS_URL = '/api/v1/claims'

/** Never throws: every outcome is a SubmitClaimResult. */
export async function submitClaim(
  request: SubmitClaimRequest,
  signal?: AbortSignal,
): Promise<SubmitClaimResult> {
  let response: Response
  try {
    response = await fetch(CLAIMS_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      signal,
    })
  } catch {
    return { kind: 'failure' }
  }

  try {
    if (response.status === 201) {
      const body: unknown = await response.json()
      return isSubmittedClaim(body) ? { kind: 'success', claim: body } : { kind: 'failure' }
    }
    if (response.status === 400) {
      const body: unknown = await response.json()
      const errors = readFieldErrors(body)
      // A 400 without field errors (e.g. malformed JSON) can't be shown next to an input
      return errors.length > 0 ? { kind: 'field-errors', errors } : { kind: 'failure' }
    }
  } catch {
    // The body wasn't JSON
  }
  return { kind: 'failure' }
}

function isSubmittedClaim(body: unknown): body is SubmittedClaim {
  return isRecord(body) && typeof body.claimNumber === 'string' && body.claimNumber !== ''
}

function readFieldErrors(body: unknown): ApiFieldError[] {
  if (!isRecord(body) || !Array.isArray(body.errors)) {
    return []
  }
  return body.errors.filter(
    (entry): entry is ApiFieldError =>
      isRecord(entry) && typeof entry.field === 'string' && typeof entry.message === 'string',
  )
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
