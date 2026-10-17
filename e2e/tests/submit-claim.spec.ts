import { expect, test } from '@playwright/test'
import { CLAIM_NUMBER, fillClaimForm, readClaimNumber, submitClaim, uniqueEmail } from '../support/claimForm'
import { waitForEmailTo } from '../support/mailpit'

// The automated version of the demo: form -> confirmation -> email in Mailpit.
test('a submitted claim shows a claim number and emails the same number to the customer', async ({
  page,
  request,
}) => {
  test.setTimeout(60_000)
  const email = uniqueEmail()

  await page.goto('/')
  await fillClaimForm(page, email)
  await submitClaim(page)

  await expect(page.getByRole('heading', { name: 'Claim received' })).toBeFocused()
  const claimNumber = await readClaimNumber(page)
  expect(claimNumber).toMatch(CLAIM_NUMBER)
  await expect(page.getByText(`We've sent a confirmation to ${email}`)).toBeVisible()

  const message = await waitForEmailTo(request, email)
  expect(message.To.map((recipient) => recipient.Address)).toEqual([email])
  expect(message.Subject).toContain(claimNumber)
  expect(message.Text).toContain(claimNumber)
  expect(message.Text).toContain('Amara')
})
