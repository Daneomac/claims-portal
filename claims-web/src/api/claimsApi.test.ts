import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../test/server'
import { submitClaim, type SubmitClaimRequest } from './claimsApi'

const request: SubmitClaimRequest = {
  policyholder: { firstName: 'Jane', lastName: 'Doe', email: 'jane.doe@example.com' },
  policyNumber: 'RRM-12345678',
  loss: { date: '2026-10-12', type: 'WATER', description: 'Burst pipe in the basement.' },
  propertyAddress: { street: '123 Main St', city: 'Winnipeg', province: 'MB', postalCode: 'R3C 1A1' },
  declarationAccepted: true,
}

const CLAIMS = '/api/v1/claims'

describe('submitClaim', () => {
  it('posts the request as JSON and returns the claim on 201', async () => {
    let received: unknown
    let contentType: string | null = null
    server.use(
      http.post(CLAIMS, async ({ request: incoming }) => {
        received = await incoming.json()
        contentType = incoming.headers.get('Content-Type')
        return HttpResponse.json(
          { claimNumber: 'HC-2026-000123', status: 'RECEIVED', submittedAt: '2026-10-17T15:00:00Z' },
          { status: 201 },
        )
      }),
    )

    const result = await submitClaim(request)

    expect(result).toEqual({
      kind: 'success',
      claim: { claimNumber: 'HC-2026-000123', status: 'RECEIVED', submittedAt: '2026-10-17T15:00:00Z' },
    })
    expect(received).toEqual(request)
    expect(contentType).toBe('application/json')
  })

  it('returns the field errors from a 400 problem detail', async () => {
    server.use(
      http.post(CLAIMS, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Bad Request',
            status: 400,
            errors: [
              { field: 'loss.date', message: "The date of loss can't be in the future" },
              { field: 'policyNumber', message: 'Enter a policy number like RRM-12345678' },
            ],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    expect(await submitClaim(request)).toEqual({
      kind: 'field-errors',
      errors: [
        { field: 'loss.date', message: "The date of loss can't be in the future" },
        { field: 'policyNumber', message: 'Enter a policy number like RRM-12345678' },
      ],
    })
  })

  it('treats a 400 without field errors as a failure', async () => {
    server.use(
      http.post(CLAIMS, () => HttpResponse.json({ status: 400, detail: 'Malformed' }, { status: 400 })),
    )

    expect(await submitClaim(request)).toEqual({ kind: 'failure' })
  })

  it.each([500, 502, 503, 429])('treats a %i as a failure', async (status) => {
    server.use(http.post(CLAIMS, () => new HttpResponse('nope', { status })))

    expect(await submitClaim(request)).toEqual({ kind: 'failure' })
  })

  it('treats a network error as a failure', async () => {
    server.use(http.post(CLAIMS, () => HttpResponse.error()))

    expect(await submitClaim(request)).toEqual({ kind: 'failure' })
  })

  it('treats a 201 with an unexpected body as a failure', async () => {
    server.use(http.post(CLAIMS, () => HttpResponse.json({ ok: true }, { status: 201 })))

    expect(await submitClaim(request)).toEqual({ kind: 'failure' })
  })
})
