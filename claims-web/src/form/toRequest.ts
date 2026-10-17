import type { SubmitClaimRequest } from '../api/claimsApi'
import type { ClaimFormValues } from './claimSchema'

/** Turns validated (and trimmed) form values into the API's JSON. Empty optionals are left out. */
export function toRequest(values: ClaimFormValues): SubmitClaimRequest {
  const { policyholder, loss, propertyAddress } = values
  return {
    policyholder: { ...policyholder, phone: policyholder.phone || undefined },
    policyNumber: values.policyNumber,
    loss: {
      ...loss,
      estimatedAmount: loss.estimatedAmount === '' ? undefined : Number(loss.estimatedAmount),
    },
    propertyAddress: { ...propertyAddress, unit: propertyAddress.unit || undefined },
    declarationAccepted: values.declarationAccepted,
  }
}
