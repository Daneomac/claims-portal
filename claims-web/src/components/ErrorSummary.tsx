import type { Ref } from 'react'
import { fieldId } from './fieldId'
import styles from './ErrorSummary.module.css'

export interface SummaryError {
  /** The form field name, e.g. "loss.date" */
  name: string
  message: string
}

interface ErrorSummaryProps {
  errors: SummaryError[]
  /** The parent focuses this element after a failed submit */
  ref: Ref<HTMLElement>
}

/** Lists every problem at the top of the form, each linking to the input that needs fixing. */
export function ErrorSummary({ errors, ref }: ErrorSummaryProps) {
  return (
    <section ref={ref} tabIndex={-1} aria-labelledby="error-summary-heading" className={styles.summary}>
      <h2 id="error-summary-heading" className={styles.heading}>
        There is a problem
      </h2>
      <ul className={styles.list}>
        {errors.map(({ name, message }) => (
          <li key={name}>
            <a
              href={`#${fieldId(name)}`}
              onClick={(event) => {
                // Move focus to the input itself, not just scroll near it
                event.preventDefault()
                document.getElementById(fieldId(name))?.focus()
              }}
            >
              {message}
            </a>
          </li>
        ))}
      </ul>
    </section>
  )
}
