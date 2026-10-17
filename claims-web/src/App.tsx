import { useState } from 'react'
import { ClaimForm } from './ClaimForm'
import { Confirmation, type ConfirmationDetails } from './components/Confirmation'
import styles from './App.module.css'

function App() {
  // null while the customer is filling in the form
  const [confirmation, setConfirmation] = useState<ConfirmationDetails | null>(null)

  return (
    <>
      <a href="#main" className={styles.skipLink}>
        Skip to main content
      </a>
      <header className={styles.header}>
        <div className={styles.headerInner}>Home insurance claims</div>
      </header>
      <main id="main" tabIndex={-1} className={styles.main}>
        <h1 className={styles.title}>Submit a home insurance claim</h1>
        {confirmation ? (
          <Confirmation {...confirmation} onSubmitAnother={() => setConfirmation(null)} />
        ) : (
          // Showing the form again mounts it fresh, so it starts empty
          <ClaimForm onSuccess={setConfirmation} />
        )}
      </main>
      <footer className={styles.footer}>
        Demonstration application. It is not connected to a real insurer.
      </footer>
    </>
  )
}

export default App
