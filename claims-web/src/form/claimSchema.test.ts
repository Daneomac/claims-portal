import { describe, expect, it } from 'vitest'
import { PROVINCE_CODES } from '../api/claimsApi'
import {
  DESCRIPTION_MAX_LENGTH,
  DESCRIPTION_MIN_LENGTH,
  amountProblem,
  characterCount,
  createClaimSchema,
  lossDateProblem,
  type ClaimFormValues,
} from './claimSchema'
import { addDays, isIsoDate, todayInBusinessZone } from './dates'
import { toRequest } from './toRequest'

const TODAY = '2026-10-17'
const schema = createClaimSchema(() => TODAY)

function validValues(): ClaimFormValues {
  return {
    policyholder: {
      firstName: 'Jane',
      lastName: 'Doe',
      email: 'jane.doe@example.com',
      phone: '204-555-0142',
    },
    policyNumber: 'RRM-12345678',
    loss: {
      date: '2026-10-12',
      type: 'WATER',
      description: 'Burst pipe in the basement flooded the finished rec room.',
      estimatedAmount: '8500.00',
    },
    propertyAddress: {
      street: '123 Main St',
      unit: '',
      city: 'Winnipeg',
      province: 'MB',
      postalCode: 'R3C 1A1',
    },
    declarationAccepted: true,
  }
}

/** Validates `values` with one change applied; returns the messages by field path. */
function errorsFor(change: (values: ClaimFormValues) => void): Record<string, string> {
  const values = validValues()
  change(values)
  const result = schema.safeParse(values)
  if (result.success) {
    return {}
  }
  // The first message per field is the one the form shows
  const messages: Record<string, string> = {}
  for (const issue of result.error.issues) {
    messages[issue.path.join('.')] ??= issue.message
  }
  return messages
}

describe('claim schema', () => {
  it('accepts a complete, valid claim', () => {
    expect(schema.safeParse(validValues()).success).toBe(true)
  })

  it('accepts the optional fields left empty', () => {
    const errors = errorsFor((v) => {
      v.policyholder.phone = ''
      v.loss.estimatedAmount = ''
      v.propertyAddress.unit = ''
    })
    expect(errors).toEqual({})
  })

  it('reports every missing required field', () => {
    const errors = errorsFor((v) => {
      v.policyholder.firstName = ''
      v.policyholder.lastName = '  '
      v.policyholder.email = ''
      v.policyNumber = ''
      v.loss.date = ''
      v.loss.description = ''
      v.propertyAddress.street = ''
      v.propertyAddress.city = ''
      v.propertyAddress.postalCode = ''
      v.declarationAccepted = false
      // What a select sitting on its placeholder produces
      ;(v.loss as { type: string }).type = ''
      ;(v.propertyAddress as { province: string }).province = ''
    })

    expect(errors).toEqual({
      'policyholder.firstName': 'Enter your first name',
      'policyholder.lastName': 'Enter your last name',
      'policyholder.email': 'Enter your email address',
      policyNumber: 'Enter your policy number',
      'loss.date': 'Enter the date of loss',
      'loss.type': 'Choose the type of loss',
      'loss.description': 'Describe what happened',
      'propertyAddress.street': 'Enter the street address',
      'propertyAddress.city': 'Enter the city',
      'propertyAddress.province': 'Choose the province or territory',
      'propertyAddress.postalCode': 'Enter the postal code',
      declarationAccepted: "Confirm that the information you've provided is accurate",
    })
  })

  describe('names', () => {
    it.each(["Anne-Marie", "O'Brien", 'José', 'St. John', 'Zoë Müller'])('accepts %s', (name) => {
      expect(errorsFor((v) => (v.policyholder.firstName = name))).toEqual({})
    })

    it.each(['J4ne', 'Jane_', 'Jane@Doe', '<script>'])('rejects %s', (name) => {
      expect(errorsFor((v) => (v.policyholder.firstName = name))).toHaveProperty(
        ['policyholder.firstName'],
        'First name can only contain letters, spaces, apostrophes, hyphens and periods',
      )
    })

    it('allows 100 characters and rejects 101', () => {
      expect(errorsFor((v) => (v.policyholder.lastName = 'a'.repeat(100)))).toEqual({})
      expect(errorsFor((v) => (v.policyholder.lastName = 'a'.repeat(101)))).toHaveProperty(
        ['policyholder.lastName'],
        'Last name must be 100 characters or fewer',
      )
    })
  })

  describe('email', () => {
    it.each(['jane@example.com', 'a.b+tag@mail.example.co.uk'])('accepts %s', (email) => {
      expect(errorsFor((v) => (v.policyholder.email = email))).toEqual({})
    })

    it.each(['jane', 'jane@', 'jane@example', '@example.com', 'jane doe@example.com'])(
      'rejects %s',
      (email) => {
        expect(errorsFor((v) => (v.policyholder.email = email))).toHaveProperty(
          ['policyholder.email'],
          'Enter a valid email address, like name@example.com',
        )
      },
    )

    it('rejects more than 254 characters', () => {
      const tooLong = `${'a'.repeat(250)}@example.com`
      expect(errorsFor((v) => (v.policyholder.email = tooLong))).toHaveProperty(
        ['policyholder.email'],
        'Email address must be 254 characters or fewer',
      )
    })
  })

  describe('phone', () => {
    it.each(['204-555-0142', '(204) 555-0142', '+1 204 555 0142', '2045550142'])(
      'accepts %s',
      (phone) => {
        expect(errorsFor((v) => (v.policyholder.phone = phone))).toEqual({})
      },
    )

    it.each(['555-0142', '104-555-0142', '204-155-0142', 'call me'])('rejects %s', (phone) => {
      expect(errorsFor((v) => (v.policyholder.phone = phone))).toHaveProperty(
        ['policyholder.phone'],
        'Enter a phone number like 204-555-0142',
      )
    })
  })

  describe('policy number', () => {
    it.each(['RRM-12345678', 'rrm-12345678', '  RRM-12345678  '])('accepts %j', (policyNumber) => {
      expect(errorsFor((v) => (v.policyNumber = policyNumber))).toEqual({})
    })

    it.each(['RRM-1234567', 'RRM-123456789', 'RRM12345678', 'ABC-12345678', 'RRM-1234567A'])(
      'rejects %s',
      (policyNumber) => {
        expect(errorsFor((v) => (v.policyNumber = policyNumber))).toHaveProperty(
          'policyNumber',
          'Enter a policy number like RRM-12345678',
        )
      },
    )
  })

  describe('loss date', () => {
    it('accepts today', () => {
      expect(lossDateProblem(TODAY, TODAY)).toBeNull()
    })

    it('rejects tomorrow', () => {
      expect(lossDateProblem('2026-10-18', TODAY)).toBe("The date of loss can't be in the future")
    })

    it('accepts exactly 365 days ago and rejects 366', () => {
      expect(lossDateProblem(addDays(TODAY, -365), TODAY)).toBeNull()
      expect(lossDateProblem(addDays(TODAY, -366), TODAY)).toBe(
        "The date of loss can't be more than 365 days ago",
      )
    })

    it('rejects a missing date and an impossible one', () => {
      expect(lossDateProblem('', TODAY)).toBe('Enter the date of loss')
      expect(lossDateProblem('2026-02-30', TODAY)).toBe('Enter a valid date of loss')
      expect(lossDateProblem('12/10/2026', TODAY)).toBe('Enter a valid date of loss')
    })

    it('is checked by the schema against the injected date', () => {
      expect(errorsFor((v) => (v.loss.date = '2026-10-18'))).toHaveProperty(
        ['loss.date'],
        "The date of loss can't be in the future",
      )
    })
  })

  describe('description', () => {
    it('allows 20 characters and rejects 19', () => {
      expect(errorsFor((v) => (v.loss.description = 'x'.repeat(DESCRIPTION_MIN_LENGTH)))).toEqual({})
      expect(
        errorsFor((v) => (v.loss.description = 'x'.repeat(DESCRIPTION_MIN_LENGTH - 1))),
      ).toHaveProperty(['loss.description'], 'Description must be at least 20 characters')
    })

    it('does not count surrounding spaces', () => {
      expect(errorsFor((v) => (v.loss.description = `  ${'x'.repeat(19)}  `))).toHaveProperty([
        'loss.description',
      ])
    })

    it('allows 2000 characters and rejects 2001', () => {
      expect(errorsFor((v) => (v.loss.description = 'x'.repeat(DESCRIPTION_MAX_LENGTH)))).toEqual({})
      expect(
        errorsFor((v) => (v.loss.description = 'x'.repeat(DESCRIPTION_MAX_LENGTH + 1))),
      ).toHaveProperty(['loss.description'], 'Description must be 2000 characters or fewer')
    })

    it('counts an emoji as one character, as the server does', () => {
      expect(characterCount('a😀b')).toBe(3)
    })
  })

  describe('estimated amount', () => {
    it.each(['', '0.01', '1', '8500', '8500.5', '8500.50', '10000000', '10000000.00'])(
      'accepts %j',
      (amount) => {
        expect(amountProblem(amount)).toBeNull()
      },
    )

    it('rejects zero', () => {
      expect(amountProblem('0')).toBe('Estimated amount must be more than $0')
      expect(amountProblem('0.00')).toBe('Estimated amount must be more than $0')
    })

    it('rejects more than 2 decimals', () => {
      expect(amountProblem('1.234')).toBe('Estimated amount can have at most 2 decimal places')
    })

    it('rejects more than 10,000,000', () => {
      expect(amountProblem('10000000.01')).toBe("Estimated amount can't be more than $10,000,000")
    })

    it.each(['abc', '-5', '1,000', '$100', '1e5', '.5'])('rejects %s as not a number', (amount) => {
      expect(amountProblem(amount)).toBe('Enter the amount as a number, like 8500 or 8500.50')
    })
  })

  describe('address', () => {
    it('limits street to 200, unit to 20 and city to 100 characters', () => {
      expect(
        errorsFor((v) => {
          v.propertyAddress.street = 'a'.repeat(200)
          v.propertyAddress.unit = 'a'.repeat(20)
          v.propertyAddress.city = 'a'.repeat(100)
        }),
      ).toEqual({})

      expect(
        errorsFor((v) => {
          v.propertyAddress.street = 'a'.repeat(201)
          v.propertyAddress.unit = 'a'.repeat(21)
          v.propertyAddress.city = 'a'.repeat(101)
        }),
      ).toEqual({
        'propertyAddress.street': 'Street address must be 200 characters or fewer',
        'propertyAddress.unit': 'Unit must be 20 characters or fewer',
        'propertyAddress.city': 'City must be 100 characters or fewer',
      })
    })

    it('accepts all 13 province and territory codes', () => {
      expect(PROVINCE_CODES).toHaveLength(13)
      for (const province of PROVINCE_CODES) {
        expect(errorsFor((v) => (v.propertyAddress.province = province))).toEqual({})
      }
    })

    it('rejects a code that is not a Canadian province', () => {
      expect(
        errorsFor((v) => ((v.propertyAddress as { province: string }).province = 'TX')),
      ).toHaveProperty(['propertyAddress.province'], 'Choose the province or territory')
    })

    it.each(['R3C 1A1', 'r3c1a1', 'R3C-1A1', '  R3C 1A1 ', 'K1A 0B1'])(
      'accepts the postal code %j',
      (postalCode) => {
        expect(errorsFor((v) => (v.propertyAddress.postalCode = postalCode))).toEqual({})
      },
    )

    // Canada Post never uses D, F, I, O, Q or U, and W and Z are never the first letter
    it.each(['12345', 'R3C 1A', 'R3C 1A11', 'D3C 1A1', 'W3C 1A1', 'R3C 1U1', 'R3C 1AO'])(
      'rejects the postal code %j',
      (postalCode) => {
        expect(errorsFor((v) => (v.propertyAddress.postalCode = postalCode))).toHaveProperty(
          ['propertyAddress.postalCode'],
          'Enter a postal code like R3C 1A1',
        )
      },
    )
  })

  it('requires the declaration to be checked', () => {
    expect(errorsFor((v) => (v.declarationAccepted = false))).toHaveProperty(
      'declarationAccepted',
      "Confirm that the information you've provided is accurate",
    )
  })

  it('trims values it accepts', () => {
    const values = validValues()
    values.policyholder.firstName = '  Jane  '
    values.policyNumber = ' RRM-12345678 '
    const parsed = schema.parse(values)
    expect(parsed.policyholder.firstName).toBe('Jane')
    expect(parsed.policyNumber).toBe('RRM-12345678')
  })
})

describe('toRequest', () => {
  it('produces the API shape and leaves empty optional fields out', () => {
    const values = validValues()
    values.policyholder.phone = ''
    values.loss.estimatedAmount = ''

    expect(JSON.parse(JSON.stringify(toRequest(values)))).toEqual({
      policyholder: { firstName: 'Jane', lastName: 'Doe', email: 'jane.doe@example.com' },
      policyNumber: 'RRM-12345678',
      loss: {
        date: '2026-10-12',
        type: 'WATER',
        description: 'Burst pipe in the basement flooded the finished rec room.',
      },
      propertyAddress: { street: '123 Main St', city: 'Winnipeg', province: 'MB', postalCode: 'R3C 1A1' },
      declarationAccepted: true,
    })
  })

  it('sends the estimated amount as a number and keeps optional values that were given', () => {
    const values = validValues()
    values.propertyAddress.unit = '4B'

    const request = toRequest(values)

    expect(request.loss.estimatedAmount).toBe(8500)
    expect(request.policyholder.phone).toBe('204-555-0142')
    expect(request.propertyAddress.unit).toBe('4B')
  })
})

describe('dates', () => {
  it('works out today in Winnipeg, not UTC', () => {
    // 03:00 UTC on the 17th is still 10 pm on the 16th in Winnipeg (CDT, UTC-5)
    expect(todayInBusinessZone(new Date('2026-10-17T03:00:00Z'))).toBe('2026-10-16')
    expect(todayInBusinessZone(new Date('2026-10-17T15:00:00Z'))).toBe('2026-10-17')
  })

  it('follows the clock change in winter (CST, UTC-6)', () => {
    expect(todayInBusinessZone(new Date('2026-12-01T05:30:00Z'))).toBe('2026-11-30')
  })

  it('adds days across month and year ends', () => {
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01')
    expect(addDays('2026-01-01', -1)).toBe('2025-12-31')
    expect(addDays('2024-03-01', -1)).toBe('2024-02-29')
  })

  it('recognises real calendar dates only', () => {
    expect(isIsoDate('2024-02-29')).toBe(true)
    expect(isIsoDate('2026-02-29')).toBe(false)
    expect(isIsoDate('2026-1-5')).toBe(false)
  })
})
