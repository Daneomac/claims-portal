import { expect, test } from '@playwright/test'
import { submitClaim } from '../support/claimForm'

test('submitting the empty form shows the errors and sends nothing to the API', async ({ page }) => {
  const apiCalls: string[] = []
  await page.route('**/api/**', (route) => {
    apiCalls.push(route.request().url())
    return route.continue()
  })

  await page.goto('/')
  await submitClaim(page)

  // The summary lists every problem and takes focus
  const summary = page.getByRole('region', { name: 'There is a problem' })
  await expect(summary).toBeVisible()
  await expect(summary).toBeFocused()
  await expect(summary.getByRole('link', { name: 'Enter your first name' })).toBeVisible()
  await expect(summary.getByRole('link', { name: 'Enter your policy number' })).toBeVisible()
  await expect(summary.getByRole('link', { name: 'Choose the type of loss' })).toBeVisible()
  await expect(summary.getByRole('link', { name: /Confirm that the information/ })).toBeVisible()

  // Each field also shows its own message, linked to the input for screen readers
  const firstName = page.getByRole('textbox', { name: 'First name' })
  await expect(firstName).toHaveAttribute('aria-invalid', 'true')
  await expect(firstName).toHaveAccessibleDescription(/Enter your first name/)
  await expect(page.getByRole('textbox', { name: 'Policy number' })).toHaveAccessibleDescription(
    /Enter your policy number/,
  )
  await expect(page.getByRole('combobox', { name: 'Province or territory' })).toHaveAccessibleDescription(
    /Choose the province or territory/,
  )

  // A summary link moves focus to the field to fix
  await summary.getByRole('link', { name: 'Enter your first name' }).click()
  await expect(firstName).toBeFocused()

  expect(apiCalls).toEqual([])
})
