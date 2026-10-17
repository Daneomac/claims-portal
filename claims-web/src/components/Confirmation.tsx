import { useEffect, useRef } from 'react'
import styles from './Confirmation.module.css'

export interface ConfirmationDetails {
  claimNumber: string
  email: string
}

interface ConfirmationProps extends ConfirmationDetails {
  onSubmitAnother: () => void
}

/** Replaces the form once the claim is accepted. */
export function Confirmation({ claimNumber, email, onSubmitAnother }: ConfirmationProps) {
  const headingRef = useRef<HTMLHeadingElement>(null)

  // The form just disappeared, so move focus to the new content. Screen readers then
  // announce the heading, and keyboard users continue from here instead of the top of the page.
  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  return (
    <section aria-labelledby="confirmation-heading" className={styles.panel}>
      <h2 id="confirmation-heading" ref={headingRef} tabIndex={-1} className={styles.heading}>
        Claim received
      </h2>
      <p className={styles.label}>Your claim number</p>
      <p className={styles.claimNumber}>{claimNumber}</p>
      <p>
        We've sent a confirmation to <strong>{email}</strong> — please check your email (and spam
        folder).
      </p>
      <p>
        Keep your claim number handy and quote it if you contact us. An adjuster will be in touch
        within 2 business days.
      </p>
      <button type="button" className={styles.button} onClick={onSubmitAnother}>
        Submit another claim
      </button>
    </section>
  )
}
