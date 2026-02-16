export function generateCorrelationId(): string {
  return crypto.randomUUID();
}
