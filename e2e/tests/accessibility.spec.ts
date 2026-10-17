import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { fillClaimForm, readClaimNumber, submitClaim, uniqueEmail } from '../support/claimForm'

/** Fails on serious and critical WCAG problems; minor and moderate ones are left to review. */
async function expectNoSeriousViolations(page: Page) {
  const { violations } = await new AxeBuilder({ page }).analyze()
  const serious = violations.filter(({ impact }) => impact === 'serious' || impact === 'critical')
  expect(serious, JSON.stringify(serious, null, 2)).toEqual([])
}

test('the claim form has no serious accessibility violations', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Submit a home insurance claim' })).toBeVisible()
  await expectNoSeriousViolations(page)
})

test('the form with validation errors has no serious accessibility violations', async ({ page }) => {
  await page.goto('/')
  await submitClaim(page)
  await expect(page.getByRole('region', { name: 'There is a problem' })).toBeVisible()
  await expectNoSeriousViolations(page)
})

test('the confirmation has no serious accessibility violations', async ({ page }) => {
  await page.goto('/')
  await fillClaimForm(page, uniqueEmail())
  await submitClaim(page)
  await readClaimNumber(page)
  await expectNoSeriousViolations(page)
})
