/** The DOM id for a form field, e.g. "policyholder.firstName" -> "policyholder-firstName". */
export function fieldId(name: string): string {
  return name.replaceAll('.', '-')
}
