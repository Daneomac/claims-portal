import type { FieldPath } from 'react-hook-form'
import type { ClaimFormValues } from './claimSchema'

export type FieldName = FieldPath<ClaimFormValues>

/**
 * Every input, in the order it appears on the page. The error summary lists problems in this
 * order, and server errors are only applied to names found here.
 */
export const FIELD_ORDER: readonly FieldName[] = [
  'policyholder.firstName',
  'policyholder.lastName',
  'policyholder.email',
  'policyholder.phone',
  'policyNumber',
  'loss.date',
  'loss.type',
  'loss.description',
  'loss.estimatedAmount',
  'propertyAddress.street',
  'propertyAddress.unit',
  'propertyAddress.city',
  'propertyAddress.province',
  'propertyAddress.postalCode',
  'declarationAccepted',
]

export function isFieldName(name: string): name is FieldName {
  return (FIELD_ORDER as readonly string[]).includes(name)
}
