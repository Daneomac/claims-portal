import { z } from 'zod'
import { LOSS_TYPES, PROVINCE_CODES } from '../api/claimsApi'
import { addDays, isIsoDate, todayInBusinessZone } from './dates'

// These rules mirror the server's (claims-api SubmitClaimRequest and LossDateRule) so most
// mistakes are caught before a round trip. The server stays authoritative: it re-checks
// everything, and its messages are shown if it disagrees.

export const MAX_LOSS_AGE_DAYS = 365
export const DESCRIPTION_MIN_LENGTH = 20
export const DESCRIPTION_MAX_LENGTH = 2000
export const MAX_ESTIMATED_AMOUNT = 10_000_000

// Same patterns as the Java side. Values are trimmed before they're tested.
const NAME = /^[\p{L}\p{M} '.-]+$/u
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const PHONE = /^(\+?1[ .-]?)?(\([2-9]\d{2}\)|[2-9]\d{2})[ .-]?[2-9]\d{2}[ .-]?\d{4}$/
const POLICY_NUMBER = /^RRM-\d{8}$/i
const POSTAL_CODE = /^[ABCEGHJ-NPRSTVXY]\d[ABCEGHJ-NPRSTV-Z][ -]?\d[ABCEGHJ-NPRSTV-Z]\d$/i

/**
 * Characters as people count them (an emoji is one). The server's @Size counts UTF-16 units,
 * where most emoji count as two, so text heavy with emoji near the limit can pass here and be
 * rejected by the server; its message then shows on the field.
 */
export function characterCount(text: string): number {
  return Array.from(text).length
}

/** The message for a bad loss date, or null when it's acceptable. Exported for testing. */
export function lossDateProblem(value: string, today: string): string | null {
  if (value === '') {
    return 'Enter the date of loss'
  }
  if (!isIsoDate(value)) {
    return 'Enter a valid date of loss'
  }
  if (value > today) {
    return "The date of loss can't be in the future"
  }
  if (value < addDays(today, -MAX_LOSS_AGE_DAYS)) {
    return `The date of loss can't be more than ${MAX_LOSS_AGE_DAYS} days ago`
  }
  return null
}

/** The message for a bad estimated amount, or null. An empty value is fine: it's optional. */
export function amountProblem(value: string): string | null {
  if (value === '') {
    return null
  }
  if (!/^\d+(\.\d*)?$/.test(value)) {
    return 'Enter the amount as a number, like 8500 or 8500.50'
  }
  if (!/^\d+(\.\d{1,2})?$/.test(value)) {
    return 'Estimated amount can have at most 2 decimal places'
  }
  const amount = Number(value)
  if (amount <= 0) {
    return 'Estimated amount must be more than $0'
  }
  if (amount > MAX_ESTIMATED_AMOUNT) {
    return "Estimated amount can't be more than $10,000,000"
  }
  return null
}

function personName(label: string, capitalised: string) {
  return z
    .string()
    .trim()
    .min(1, `Enter your ${label}`)
    .max(100, `${capitalised} must be 100 characters or fewer`)
    .regex(
      NAME,
      `${capitalised} can only contain letters, spaces, apostrophes, hyphens and periods`,
    )
}

/** `today` is injectable so tests can pin the date. It's called on every validation. */
export function createClaimSchema(today: () => string = todayInBusinessZone) {
  return z.object({
    policyholder: z.object({
      firstName: personName('first name', 'First name'),
      lastName: personName('last name', 'Last name'),
      email: z
        .string()
        .trim()
        .min(1, 'Enter your email address')
        .max(254, 'Email address must be 254 characters or fewer')
        .regex(EMAIL, 'Enter a valid email address, like name@example.com'),
      phone: z
        .string()
        .trim()
        .refine((v) => v === '' || PHONE.test(v), 'Enter a phone number like 204-555-0142'),
    }),
    policyNumber: z
      .string()
      .trim()
      .min(1, 'Enter your policy number')
      .regex(POLICY_NUMBER, 'Enter a policy number like RRM-12345678'),
    loss: z.object({
      date: z.string().superRefine((value, ctx) => {
        const problem = lossDateProblem(value, today())
        if (problem) {
          ctx.addIssue({ code: 'custom', message: problem })
        }
      }),
      type: z.enum(LOSS_TYPES, { error: 'Choose the type of loss' }),
      description: z
        .string()
        .trim()
        .min(1, 'Describe what happened')
        .refine(
          (v) => characterCount(v) >= DESCRIPTION_MIN_LENGTH,
          `Description must be at least ${DESCRIPTION_MIN_LENGTH} characters`,
        )
        .refine(
          (v) => characterCount(v) <= DESCRIPTION_MAX_LENGTH,
          `Description must be ${DESCRIPTION_MAX_LENGTH} characters or fewer`,
        ),
      // Kept as the typed text; toRequest() turns it into a number
      estimatedAmount: z
        .string()
        .trim()
        .superRefine((value, ctx) => {
          const problem = amountProblem(value)
          if (problem) {
            ctx.addIssue({ code: 'custom', message: problem })
          }
        }),
    }),
    propertyAddress: z.object({
      street: z
        .string()
        .trim()
        .min(1, 'Enter the street address')
        .max(200, 'Street address must be 200 characters or fewer'),
      unit: z.string().trim().max(20, 'Unit must be 20 characters or fewer'),
      city: z
        .string()
        .trim()
        .min(1, 'Enter the city')
        .max(100, 'City must be 100 characters or fewer'),
      province: z.enum(PROVINCE_CODES, { error: 'Choose the province or territory' }),
      postalCode: z
        .string()
        .trim()
        .min(1, 'Enter the postal code')
        .regex(POSTAL_CODE, 'Enter a postal code like R3C 1A1'),
    }),
    declarationAccepted: z
      .boolean()
      .refine((v) => v, "Confirm that the information you've provided is accurate"),
  })
}

export const claimSchema = createClaimSchema()

/** The form's values: the same shape as the API request, but every input is text. */
export type ClaimFormValues = z.infer<typeof claimSchema>
