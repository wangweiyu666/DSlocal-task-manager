export function isTemperatureTenthsC(value: unknown): value is number {
  return Number.isInteger(value) && Number(value) >= 350 && Number(value) <= 420;
}

export function formatTemperature(value: number): string {
  return `${(value / 10).toFixed(1)}℃`;
}
