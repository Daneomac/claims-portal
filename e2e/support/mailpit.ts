import { expect, type APIRequestContext } from '@playwright/test'

const MAILPIT_URL = process.env.MAILPIT_URL ?? 'http://localhost:8025'

/** The parts of a Mailpit message that the tests look at. */
export interface Email {
  Subject: string
  Text: string
  To: { Address: string }[]
}

/**
 * Waits for the notification service to deliver an email to `address` and returns it.
 *
 * The email arrives asynchronously (outbox relay, then RabbitMQ, then the consumer, then SMTP),
 * so this polls Mailpit's REST API instead of sleeping for a fixed time.
 */
export async function waitForEmailTo(request: APIRequestContext, address: string): Promise<Email> {
  let id: string | undefined

  await expect
    .poll(
      async () => {
        const search = await request.get(`${MAILPIT_URL}/api/v1/search`, {
          params: { query: `to:${address}` },
        })
        expect(search.ok()).toBe(true)
        const { messages } = (await search.json()) as { messages: { ID: string }[] }
        id = messages[0]?.ID
        return id
      },
      {
        message: `an email to ${address} should arrive in Mailpit`,
        timeout: 30_000,
        intervals: [500, 1_000, 2_000],
      },
    )
    .toBeDefined()

  const message = await request.get(`${MAILPIT_URL}/api/v1/message/${id}`)
  expect(message.ok()).toBe(true)
  return (await message.json()) as Email
}
