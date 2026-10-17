// Dates are handled as "YYYY-MM-DD" strings, the format of <input type="date"> and of the
// API. They compare correctly as plain strings, which avoids time-zone surprises with Date.

/** The same zone the server uses to decide what "today" is (claims.time-zone). */
export const BUSINESS_TIME_ZONE = 'America/Winnipeg'

export function todayInBusinessZone(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: BUSINESS_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}`
}

export function addDays(isoDate: string, days: number): string {
  const [year, month, day] = isoDate.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10)
}

/** True for a real calendar date such as 2026-02-28, false for 2026-02-30. */
export function isIsoDate(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && addDays(value, 0) === value
}
