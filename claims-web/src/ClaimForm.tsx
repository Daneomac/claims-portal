import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { get, useForm, useWatch } from 'react-hook-form'
import { submitClaim } from './api/claimsApi'
import type { ConfirmationDetails } from './components/Confirmation'
import { ErrorSummary, type SummaryError } from './components/ErrorSummary'
import { CheckboxField, SelectField, TextAreaField, TextField } from './components/Field'
import {
  DESCRIPTION_MAX_LENGTH,
  DESCRIPTION_MIN_LENGTH,
  MAX_LOSS_AGE_DAYS,
  characterCount,
  claimSchema,
  type ClaimFormValues,
} from './form/claimSchema'
import { addDays, todayInBusinessZone } from './form/dates'
import { FIELD_ORDER, isFieldName } from './form/fieldNames'
import { lossTypeOptions, provinceOptions } from './form/options'
import { toRequest } from './form/toRequest'
import styles from './ClaimForm.module.css'

interface ClaimFormProps {
  onSuccess: (confirmation: ConfirmationDetails) => void
}

export function ClaimForm({ onSuccess }: ClaimFormProps) {
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors, isSubmitting, submitCount },
  } = useForm<ClaimFormValues>({
    resolver: zodResolver(claimSchema),
    // Check a field when the customer leaves it, and everything on submit. After a first
    // submit, fields re-check as they're edited so errors clear as soon as they're fixed.
    mode: 'onBlur',
    // The error summary takes focus instead of the first invalid input (see the effect below)
    shouldFocusError: false,
    defaultValues: {
      policyholder: { firstName: '', lastName: '', email: '', phone: '' },
      policyNumber: '',
      loss: { date: '', description: '', estimatedAmount: '' },
      propertyAddress: { street: '', unit: '', city: '', postalCode: '' },
      declarationAccepted: false,
    },
  })

  const [submitFailed, setSubmitFailed] = useState(false)
  const summaryRef = useRef<HTMLElement>(null)

  // Problems in page order, for the summary
  const summaryErrors: SummaryError[] = FIELD_ORDER.flatMap((name) => {
    const message: string | undefined = get(errors, name)?.message
    return message ? [{ name, message }] : []
  })
  const showSummary = submitCount > 0 && summaryErrors.length > 0

  // After a submit attempt that left errors (client-side or from the server), move focus to
  // the summary. submitCount only changes when a submit attempt finishes, so errors that
  // appear later, when the customer leaves a field, don't pull focus away from what they're doing.
  useEffect(() => {
    summaryRef.current?.focus()
  }, [submitCount])

  async function onValid(values: ClaimFormValues) {
    setSubmitFailed(false)
    const result = await submitClaim(toRequest(values))

    if (result.kind === 'success') {
      onSuccess({ claimNumber: result.claim.claimNumber, email: values.policyholder.email })
      return
    }

    if (result.kind === 'field-errors') {
      // The server's field paths are the form's field names, so each error lands on its input
      let shown = 0
      for (const { field, message } of result.errors) {
        if (isFieldName(field)) {
          setError(field, { type: 'server', message })
          shown++
        }
      }
      if (shown > 0) {
        return
      }
    }
    // A network problem, a 5xx, or errors we have no input for: keep the data, offer a retry
    setSubmitFailed(true)
  }

  const submit = handleSubmit(onValid)
  // The button is disabled while a request is in flight, but pressing Enter in a field
  // would still submit, so guard here too
  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!isSubmitting) {
      void submit()
    }
  }
  const description = useWatch({ control, name: 'loss.description' })
  const descriptionLength = characterCount(description)
  const today = todayInBusinessZone()

  return (
    <form onSubmit={onSubmit} noValidate className={styles.form}>
      <p className={styles.intro}>
        Tell us what happened and we'll open a claim. It takes about five minutes. Fields marked{' '}
        <span aria-hidden="true">*</span>
        <span className={styles.srOnly}>with an asterisk</span> are required.
      </p>

      {showSummary ? <ErrorSummary ref={summaryRef} errors={summaryErrors} /> : null}

      <fieldset className={styles.fieldset}>
        <legend>About you</legend>
        <div className={styles.row}>
          <TextField
            label="First name"
            required
            autoComplete="given-name"
            error={errors.policyholder?.firstName?.message}
            {...register('policyholder.firstName')}
          />
          <TextField
            label="Last name"
            required
            autoComplete="family-name"
            error={errors.policyholder?.lastName?.message}
            {...register('policyholder.lastName')}
          />
        </div>
        <TextField
          label="Email address"
          required
          type="email"
          autoComplete="email"
          spellCheck={false}
          hint="We'll email your claim number here."
          error={errors.policyholder?.email?.message}
          {...register('policyholder.email')}
        />
        <TextField
          label="Phone number"
          type="tel"
          autoComplete="tel"
          hint="For example 204-555-0142."
          error={errors.policyholder?.phone?.message}
          {...register('policyholder.phone')}
        />
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend>Your policy</legend>
        <TextField
          label="Policy number"
          required
          autoComplete="off"
          spellCheck={false}
          hint="Find it on your policy documents. It looks like RRM-12345678."
          error={errors.policyNumber?.message}
          {...register('policyNumber')}
        />
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend>What happened</legend>
        <TextField
          label="Date of loss"
          required
          type="date"
          min={addDays(today, -MAX_LOSS_AGE_DAYS)}
          max={today}
          hint="The day the damage happened. Claims can be made up to a year later."
          error={errors.loss?.date?.message}
          {...register('loss.date')}
        />
        <SelectField
          label="Type of loss"
          required
          placeholder="Choose the type of loss"
          options={lossTypeOptions}
          error={errors.loss?.type?.message}
          {...register('loss.type')}
        />
        <TextAreaField
          label="Describe what happened"
          required
          hint={`Include what was damaged and how. At least ${DESCRIPTION_MIN_LENGTH} characters.`}
          counter={`${descriptionLength} of ${DESCRIPTION_MAX_LENGTH} characters`}
          error={errors.loss?.description?.message}
          {...register('loss.description')}
        />
        <TextField
          label="Estimated cost of the damage (CAD)"
          inputMode="decimal"
          autoComplete="off"
          hint="Your best guess in dollars, like 8500 or 8500.50. Leave it blank if you're not sure."
          error={errors.loss?.estimatedAmount?.message}
          {...register('loss.estimatedAmount')}
        />
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend>Property address</legend>
        <p className={styles.fieldsetHint}>The home the claim is about.</p>
        <TextField
          label="Street address"
          required
          autoComplete="address-line1"
          error={errors.propertyAddress?.street?.message}
          {...register('propertyAddress.street')}
        />
        <TextField
          label="Unit or apartment"
          autoComplete="address-line2"
          error={errors.propertyAddress?.unit?.message}
          {...register('propertyAddress.unit')}
        />
        <div className={styles.row}>
          <TextField
            label="City"
            required
            autoComplete="address-level2"
            error={errors.propertyAddress?.city?.message}
            {...register('propertyAddress.city')}
          />
          <SelectField
            label="Province or territory"
            required
            placeholder="Choose a province or territory"
            options={provinceOptions}
            autoComplete="address-level1"
            error={errors.propertyAddress?.province?.message}
            {...register('propertyAddress.province')}
          />
        </div>
        <TextField
          label="Postal code"
          required
          autoComplete="postal-code"
          autoCapitalize="characters"
          spellCheck={false}
          hint="For example R3C 1A1."
          error={errors.propertyAddress?.postalCode?.message}
          {...register('propertyAddress.postalCode')}
        />
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend>Declaration</legend>
        <CheckboxField
          label="I confirm that the information I've provided is accurate and complete to the best of my knowledge."
          error={errors.declarationAccepted?.message}
          {...register('declarationAccepted')}
        />
      </fieldset>

      {submitFailed ? (
        <div role="alert" className={styles.banner}>
          <p className={styles.bannerTitle}>We couldn't submit your claim</p>
          <p>
            Something went wrong on our side or with your connection. Your answers are still here, so
            you can try again.
          </p>
          <button
            type="button"
            className={styles.secondaryButton}
            disabled={isSubmitting}
            onClick={() => void submit()}
          >
            Try again
          </button>
        </div>
      ) : null}

      <div className={styles.actions}>
        <button type="submit" className={styles.submit} disabled={isSubmitting}>
          {isSubmitting ? 'Submitting…' : 'Submit claim'}
        </button>
        <p role="status" className={styles.srOnly}>
          {isSubmitting ? 'Submitting your claim…' : ''}
        </p>
      </div>
    </form>
  )
}
