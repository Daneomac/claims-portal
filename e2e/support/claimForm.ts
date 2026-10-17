import { expect, type Page } from '@playwright/test'

/** A claim number such as HC-2026-000042 */
export const CLAIM_NUMBER = /HC-\d{4}-\d{6}/

/** A different address every time, so a run never finds another run's email in Mailpit. */
export function uniqueEmail(): string {
  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  return `e2e-${suffix}@example.com`
}

/** Five days ago as YYYY-MM-DD. Always in the past and well inside the one-year limit. */
function recentLossDate(): string {
  const date = new Date()
  date.setUTCDate(date.getUTCDate() - 5)
  return date.toISOString().slice(0, 10)
}

/** Fills in every field of the claim form, optional ones included. Doesn't submit. */
export async function fillClaimForm(page: Page, email: string): Promise<void> {
  await page.getByLabel('First name').fill('Amara')
  await page.getByLabel('Last name').fill('Okoli')
  await page.getByLabel('Email address').fill(email)
  await page.getByLabel('Phone number').fill('204-555-0142')

  await page.getByLabel('Policy number').fill('RRM-12345678')

  await page.getByLabel('Date of loss').fill(recentLossDate())
  await page.getByLabel('Type of loss').selectOption({ label: 'Water damage' })
  await page
    .getByLabel('Describe what happened')
    .fill('A pipe burst in the basement ceiling and flooded the finished rec room.')
  await page.getByLabel('Estimated cost of the damage').fill('8500.50')

  await page.getByLabel('Street address').fill('123 Portage Avenue')
  await page.getByLabel('Unit or apartment').fill('4B')
  await page.getByLabel('City').fill('Winnipeg')
  await page.getByLabel('Province or territory').selectOption({ label: 'Manitoba' })
  await page.getByLabel('Postal code').fill('R3C 1A1')

  await page.getByRole('checkbox', { name: /I confirm that the information/ }).check()
}

export async function submitClaim(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Submit claim' }).click()
}

/** Waits for the confirmation and returns the claim number it shows. */
export async function readClaimNumber(page: Page): Promise<string> {
  await expect(page.getByRole('heading', { name: 'Claim received' })).toBeVisible()
  const text = await page.getByText(CLAIM_NUMBER).first().textContent()
  return CLAIM_NUMBER.exec(text ?? '')![0]
}
