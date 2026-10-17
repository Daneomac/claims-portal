import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import App from './App'
import { addDays, todayInBusinessZone } from './form/dates'
import { server } from './test/server'

const CLAIMS = '/api/v1/claims'
const today = todayInBusinessZone()
const created = { claimNumber: 'HC-2026-000123', status: 'RECEIVED', submittedAt: '2026-10-17T15:00:00Z' }

type User = ReturnType<typeof userEvent.setup>

function setup() {
  // No artificial delay between keystrokes, which keeps the typing-heavy tests fast
  const user = userEvent.setup({ delay: null })
  render(<App />)
  return user
}

/** Fills the whole form with valid answers, except for the fields in `skip`. */
async function fillForm(user: User, overrides: { lossDate?: string; postalCode?: string } = {}) {
  await user.type(screen.getByLabelText(/first name/i), 'Jane')
  await user.type(screen.getByLabelText(/last name/i), 'Doe')
  await user.type(screen.getByLabelText(/email address/i), 'jane.doe@example.com')
  await user.type(screen.getByLabelText(/policy number/i), 'RRM-12345678')
  await user.type(screen.getByLabelText(/date of loss/i), overrides.lossDate ?? addDays(today, -5))
  await user.selectOptions(screen.getByLabelText(/type of loss/i), 'Water damage')
  await user.type(
    screen.getByLabelText(/describe what happened/i),
    'Burst pipe in the basement flooded the finished rec room.',
  )
  await user.type(screen.getByLabelText(/street address/i), '123 Main St')
  await user.type(screen.getByLabelText(/^city/i), 'Winnipeg')
  await user.selectOptions(screen.getByLabelText(/province or territory/i), 'Manitoba')
  await user.type(screen.getByLabelText(/postal code/i), overrides.postalCode ?? 'R3C 1A1')
  await user.click(screen.getByLabelText(/i confirm that the information/i))
}

/** Clicks submit on an invalid form and waits for the (asynchronous) error summary. */
async function submitExpectingErrors(user: User) {
  await user.click(submitButton())
  await screen.findByRole('region', { name: 'There is a problem' })
}

const submitButton = () => screen.getByRole('button', { name: /submit claim|submitting/i })

describe('claim form', () => {
  it('groups the questions into fieldsets with legends', () => {
    setup()

    for (const legend of [
      'About you',
      'Your policy',
      'What happened',
      'Property address',
      'Declaration',
    ]) {
      expect(screen.getByRole('group', { name: legend })).toBeInTheDocument()
    }
  })

  it('wires labels, hints and autocomplete onto the inputs', () => {
    setup()

    const email = screen.getByLabelText(/email address/i)
    expect(email).toHaveAttribute('autocomplete', 'email')
    expect(email).toHaveAttribute('aria-required', 'true')
    expect(email).toHaveAccessibleDescription("We'll email your claim number here.")
    expect(screen.getByLabelText(/first name/i)).toHaveAttribute('autocomplete', 'given-name')
    expect(screen.getByLabelText(/street address/i)).toHaveAttribute('autocomplete', 'address-line1')
    expect(screen.getByLabelText(/postal code/i)).toHaveAttribute('autocomplete', 'postal-code')
    expect(screen.getByLabelText(/phone number/i)).not.toHaveAttribute('aria-required')
  })

  it('offers all 13 provinces and territories and the six loss types', () => {
    setup()

    const provinces = within(screen.getByLabelText(/province or territory/i)).getAllByRole('option')
    expect(provinces).toHaveLength(14) // 13 plus the placeholder
    expect(screen.getByRole('option', { name: 'Newfoundland and Labrador' })).toHaveValue('NL')

    const lossTypes = within(screen.getByLabelText(/type of loss/i)).getAllByRole('option')
    expect(lossTypes).toHaveLength(7)
    expect(screen.getByRole('option', { name: 'Wind or hail' })).toHaveValue('WIND_HAIL')
  })

  it('counts the characters in the description', async () => {
    const user = setup()

    await user.type(screen.getByLabelText(/describe what happened/i), 'Hello')

    expect(screen.getByText('5 of 2000 characters')).toBeInTheDocument()
  })

  describe('validation', () => {
    it('shows required-field errors on submit and moves focus to the summary', async () => {
      const user = setup()

      await user.click(submitButton())

      // Validation is asynchronous, so wait for the summary to appear and take focus
      const summary = await screen.findByRole('region', { name: 'There is a problem' })
      await waitFor(() => expect(summary).toHaveFocus())
      for (const message of [
        'Enter your first name',
        'Enter your last name',
        'Enter your email address',
        'Enter your policy number',
        'Enter the date of loss',
        'Choose the type of loss',
        'Describe what happened',
        'Enter the street address',
        'Enter the city',
        'Choose the province or territory',
        'Enter the postal code',
        "Confirm that the information you've provided is accurate",
      ]) {
        expect(within(summary).getByRole('link', { name: message })).toBeInTheDocument()
      }
      // The same message sits next to the input, which is flagged invalid and described by it
      const firstName = screen.getByLabelText(/first name/i)
      expect(firstName).toBeInvalid()
      expect(firstName).toHaveAccessibleDescription(/enter your first name/i)
      // Optional fields are left alone
      expect(screen.getByLabelText(/phone number/i)).toBeValid()
    })

    it('moves focus to the input when a summary link is activated', async () => {
      const user = setup()
      await submitExpectingErrors(user)

      await user.click(await screen.findByRole('link', { name: 'Enter your policy number' }))

      expect(screen.getByLabelText(/policy number/i)).toHaveFocus()
    })

    it('does not call the API while the form is invalid', async () => {
      let called = false
      server.use(
        http.post(CLAIMS, () => {
          called = true
          return HttpResponse.json(created, { status: 201 })
        }),
      )
      const user = setup()

      await submitExpectingErrors(user)

      expect(called).toBe(false)
    })

    it('validates a field when the customer leaves it', async () => {
      const user = setup()

      await user.type(screen.getByLabelText(/email address/i), 'not-an-email')
      expect(screen.queryByText(/enter a valid email address/i)).not.toBeInTheDocument()
      await user.tab()

      expect(screen.getByText(/enter a valid email address/i)).toBeInTheDocument()
      expect(screen.getByLabelText(/email address/i)).toBeInvalid()
      // No summary until the customer tries to submit
      expect(screen.queryByRole('region', { name: 'There is a problem' })).not.toBeInTheDocument()
    })

    it('clears an error once the field is corrected', async () => {
      const user = setup()
      await submitExpectingErrors(user)
      expect(screen.getByLabelText(/first name/i)).toBeInvalid()

      await user.type(screen.getByLabelText(/first name/i), 'Jane')

      expect(screen.getByLabelText(/first name/i)).toBeValid()
      expect(screen.queryByRole('link', { name: 'Enter your first name' })).not.toBeInTheDocument()
    })

    it('rejects a loss date in the future', async () => {
      const user = setup()
      await fillForm(user, { lossDate: addDays(today, 1) })

      await submitExpectingErrors(user)

      expect(
        within(screen.getByRole('region', { name: 'There is a problem' })).getByRole('link', {
          name: "The date of loss can't be in the future",
        }),
      ).toBeInTheDocument()
      expect(screen.getByLabelText(/date of loss/i)).toBeInvalid()
    })

    it('rejects a loss date more than 365 days ago', async () => {
      const user = setup()
      await fillForm(user, { lossDate: addDays(today, -366) })

      await submitExpectingErrors(user)

      expect(screen.getByLabelText(/date of loss/i)).toHaveAccessibleDescription(
        /can't be more than 365 days ago/,
      )
    })

    it('rejects a bad policy number', async () => {
      const user = setup()
      await fillForm(user)
      const policy = screen.getByLabelText(/policy number/i)
      await user.clear(policy)
      await user.type(policy, 'RRM-1234')

      await submitExpectingErrors(user)

      expect(policy).toBeInvalid()
      expect(policy).toHaveAccessibleDescription(/enter a policy number like rrm-12345678/i)
    })

    it('rejects a description that is too short', async () => {
      const user = setup()
      await fillForm(user)
      const description = screen.getByLabelText(/describe what happened/i)
      await user.clear(description)
      await user.type(description, 'Too short')

      await submitExpectingErrors(user)

      expect(description).toBeInvalid()
      expect(description).toHaveAccessibleDescription(/must be at least 20 characters/)
    })

    it('rejects a bad postal code', async () => {
      const user = setup()
      await fillForm(user, { postalCode: '12345' })

      await submitExpectingErrors(user)

      expect(screen.getByLabelText(/postal code/i)).toBeInvalid()
      expect(screen.getByLabelText(/postal code/i)).toHaveAccessibleDescription(
        /enter a postal code like r3c 1a1/i,
      )
    })
  })

  describe('submitting', () => {
    it('sends the claim in the API shape and shows the confirmation with focus on its heading', async () => {
      let body: unknown
      server.use(
        http.post(CLAIMS, async ({ request }) => {
          body = await request.json()
          return HttpResponse.json(created, { status: 201 })
        }),
      )
      const user = setup()
      await fillForm(user)

      await user.click(submitButton())

      const heading = await screen.findByRole('heading', { name: 'Claim received' })
      expect(heading).toHaveFocus()
      expect(screen.getByText('HC-2026-000123')).toBeInTheDocument()
      expect(
        screen.getByText(/we've sent a confirmation to/i).textContent,
      ).toBe(
        "We've sent a confirmation to jane.doe@example.com — please check your email (and spam folder).",
      )
      expect(screen.queryByRole('button', { name: /submit claim/i })).not.toBeInTheDocument()
      expect(body).toEqual({
        policyholder: { firstName: 'Jane', lastName: 'Doe', email: 'jane.doe@example.com' },
        policyNumber: 'RRM-12345678',
        loss: {
          date: addDays(today, -5),
          type: 'WATER',
          description: 'Burst pipe in the basement flooded the finished rec room.',
        },
        propertyAddress: {
          street: '123 Main St',
          city: 'Winnipeg',
          province: 'MB',
          postalCode: 'R3C 1A1',
        },
        declarationAccepted: true,
      })
    })

    it('includes the optional fields when they are filled in', async () => {
      let body: { policyholder: { phone?: string }; loss: { estimatedAmount?: number }; propertyAddress: { unit?: string } } | undefined
      server.use(
        http.post(CLAIMS, async ({ request }) => {
          body = (await request.json()) as typeof body
          return HttpResponse.json(created, { status: 201 })
        }),
      )
      const user = setup()
      await fillForm(user)
      await user.type(screen.getByLabelText(/phone number/i), '204-555-0142')
      await user.type(screen.getByLabelText(/estimated cost/i), '8500.50')
      await user.type(screen.getByLabelText(/unit or apartment/i), '4B')

      await user.click(submitButton())

      await screen.findByRole('heading', { name: 'Claim received' })
      expect(body?.policyholder.phone).toBe('204-555-0142')
      expect(body?.loss.estimatedAmount).toBe(8500.5)
      expect(body?.propertyAddress.unit).toBe('4B')
    })

    it('disables the submit button while the request is pending', async () => {
      let release: () => void = () => {}
      const gate = new Promise<void>((resolve) => {
        release = resolve
      })
      let requests = 0
      server.use(
        http.post(CLAIMS, async () => {
          requests++
          await gate
          return HttpResponse.json(created, { status: 201 })
        }),
      )
      const user = setup()
      await fillForm(user)

      await user.click(submitButton())

      await waitFor(() => expect(submitButton()).toBeDisabled())
      expect(submitButton()).toHaveTextContent('Submitting…')
      // A second click (or Enter) can't send a duplicate
      await user.click(submitButton())
      await user.type(screen.getByLabelText(/first name/i), '{Enter}')
      release()
      await screen.findByRole('heading', { name: 'Claim received' })
      expect(requests).toBe(1)
    })

    it('starts with an empty form after "Submit another claim"', async () => {
      server.use(http.post(CLAIMS, () => HttpResponse.json(created, { status: 201 })))
      const user = setup()
      await fillForm(user)
      await user.click(submitButton())
      await screen.findByRole('heading', { name: 'Claim received' })

      await user.click(screen.getByRole('button', { name: 'Submit another claim' }))

      expect(screen.queryByRole('heading', { name: 'Claim received' })).not.toBeInTheDocument()
      expect(screen.getByLabelText(/first name/i)).toHaveValue('')
      expect(screen.getByLabelText(/i confirm that the information/i)).not.toBeChecked()
    })
  })

  describe('errors from the server', () => {
    it('maps a 400 onto the matching inputs and focuses the summary', async () => {
      server.use(
        http.post(CLAIMS, () =>
          HttpResponse.json(
            {
              status: 400,
              errors: [
                { field: 'policyNumber', message: 'Enter a policy number like RRM-12345678' },
                { field: 'loss.date', message: "The date of loss can't be more than 365 days ago" },
                { field: 'propertyAddress.postalCode', message: 'Enter a postal code like R3C 1A1' },
              ],
            },
            { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        ),
      )
      const user = setup()
      await fillForm(user)

      await user.click(submitButton())

      const summary = await screen.findByRole('region', { name: 'There is a problem' })
      await waitFor(() => expect(summary).toHaveFocus())
      expect(within(summary).getAllByRole('link')).toHaveLength(3)

      const policy = screen.getByLabelText(/policy number/i)
      expect(policy).toBeInvalid()
      expect(policy).toHaveAccessibleDescription(/enter a policy number like/i)
      expect(screen.getByLabelText(/date of loss/i)).toBeInvalid()
      expect(screen.getByLabelText(/postal code/i)).toBeInvalid()
      expect(screen.getByLabelText(/first name/i)).toBeValid()
      // Nothing is lost, and this is a field problem rather than an outage
      expect(policy).toHaveValue('RRM-12345678')
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('shows a retryable banner on a 500 and keeps the data', async () => {
      let attempts = 0
      server.use(
        http.post(CLAIMS, () => {
          attempts++
          return attempts === 1
            ? HttpResponse.json({ status: 500 }, { status: 500 })
            : HttpResponse.json(created, { status: 201 })
        }),
      )
      const user = setup()
      await fillForm(user)

      await user.click(submitButton())

      const banner = await screen.findByRole('alert')
      expect(banner).toHaveTextContent("We couldn't submit your claim")
      expect(screen.getByLabelText(/first name/i)).toHaveValue('Jane')
      expect(screen.getByLabelText(/email address/i)).toHaveValue('jane.doe@example.com')
      expect(screen.getByLabelText(/describe what happened/i)).toHaveValue(
        'Burst pipe in the basement flooded the finished rec room.',
      )
      expect(screen.getByLabelText(/province or territory/i)).toHaveValue('MB')
      expect(screen.getByLabelText(/i confirm that the information/i)).toBeChecked()
      expect(submitButton()).toBeEnabled()

      await user.click(within(banner).getByRole('button', { name: 'Try again' }))

      expect(await screen.findByRole('heading', { name: 'Claim received' })).toBeInTheDocument()
      expect(attempts).toBe(2)
    })

    it('shows the same banner when the network is down', async () => {
      server.use(http.post(CLAIMS, () => HttpResponse.error()))
      const user = setup()
      await fillForm(user)

      await user.click(submitButton())

      expect(await screen.findByRole('alert')).toHaveTextContent("We couldn't submit your claim")
      expect(screen.getByLabelText(/last name/i)).toHaveValue('Doe')
    })
  })
})
