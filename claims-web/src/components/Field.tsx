import type { ComponentProps, ReactNode } from 'react'
import { fieldId } from './fieldId'
import styles from './Field.module.css'

interface FieldShellProps {
  name: string
  label: string
  required?: boolean
  hint?: ReactNode
  error?: string
  /** Extra content under the control, such as a character counter */
  footer?: ReactNode
  children: (control: ControlAttributes) => ReactNode
}

/** What every control needs so assistive technology links it to its label, hint and error. */
interface ControlAttributes {
  id: string
  'aria-required': boolean | undefined
  'aria-invalid': boolean
  'aria-describedby': string | undefined
}

/** Label, hint and error text around a control. The control itself comes from `children`. */
function FieldShell({ name, label, required, hint, error, footer, children }: FieldShellProps) {
  const id = fieldId(name)
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ')

  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
        {required ? (
          <span aria-hidden="true" className={styles.requiredMark}>
            {' '}
            *
          </span>
        ) : (
          <span className={styles.optional}> (optional)</span>
        )}
      </label>
      {hint ? (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className={styles.error}>
          <span className={styles.visuallyHidden}>Error: </span>
          {error}
        </p>
      ) : null}
      {children({
        id,
        'aria-required': required ? true : undefined,
        'aria-invalid': Boolean(error),
        'aria-describedby': describedBy || undefined,
      })}
      {footer}
    </div>
  )
}

interface FieldChromeProps {
  label: string
  required?: boolean
  hint?: ReactNode
  error?: string
}

// The three controls below take react-hook-form's register() result (name, ref, onChange,
// onBlur) and any ordinary input attributes, such as autoComplete.

type TextFieldProps = FieldChromeProps & Omit<ComponentProps<'input'>, 'name'> & { name: string }

export function TextField({ label, required, hint, error, name, ...inputProps }: TextFieldProps) {
  return (
    <FieldShell name={name} label={label} required={required} hint={hint} error={error}>
      {(control) => <input type="text" className={styles.control} {...control} name={name} {...inputProps} />}
    </FieldShell>
  )
}

type TextAreaFieldProps = FieldChromeProps &
  Omit<ComponentProps<'textarea'>, 'name'> & { name: string; counter?: ReactNode }

export function TextAreaField({
  label,
  required,
  hint,
  error,
  name,
  counter,
  ...textAreaProps
}: TextAreaFieldProps) {
  return (
    <FieldShell
      name={name}
      label={label}
      required={required}
      hint={hint}
      error={error}
      footer={counter ? <p className={styles.counter}>{counter}</p> : undefined}
    >
      {(control) => <textarea className={styles.control} {...control} name={name} {...textAreaProps} />}
    </FieldShell>
  )
}

type SelectFieldProps = FieldChromeProps &
  Omit<ComponentProps<'select'>, 'name'> & {
    name: string
    placeholder: string
    options: readonly (readonly [value: string, label: string])[]
  }

export function SelectField({
  label,
  required,
  hint,
  error,
  name,
  placeholder,
  options,
  ...selectProps
}: SelectFieldProps) {
  return (
    <FieldShell name={name} label={label} required={required} hint={hint} error={error}>
      {(control) => (
        <select className={styles.control} {...control} name={name} {...selectProps}>
          <option value="">{placeholder}</option>
          {options.map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </select>
      )}
    </FieldShell>
  )
}

type CheckboxFieldProps = { label: ReactNode; error?: string; name: string } & Omit<
  ComponentProps<'input'>,
  'name' | 'type'
>

export function CheckboxField({ label, error, name, ...inputProps }: CheckboxFieldProps) {
  const id = fieldId(name)
  const errorId = `${id}-error`
  return (
    <div className={styles.field}>
      {error ? (
        <p id={errorId} className={styles.error}>
          <span className={styles.visuallyHidden}>Error: </span>
          {error}
        </p>
      ) : null}
      <div className={styles.checkbox}>
        <input
          type="checkbox"
          id={id}
          name={name}
          aria-required="true"
          aria-invalid={Boolean(error)}
          aria-describedby={error ? errorId : undefined}
          {...inputProps}
        />
        <label htmlFor={id}>{label}</label>
      </div>
    </div>
  )
}
