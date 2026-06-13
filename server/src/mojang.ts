const SESSION_SERVER = 'https://sessionserver.mojang.com/session/minecraft/hasJoined';

/**
 * Asks Mojang whether `username` recently called joinServer with `serverId`.
 * Returns the player's dashless UUID on success, null otherwise (Mojang
 * responds 204 with no body when there was no matching join).
 */
export async function hasJoined(username: string, serverId: string): Promise<string | null> {
  const url = `${SESSION_SERVER}?username=${encodeURIComponent(username)}&serverId=${encodeURIComponent(serverId)}`;
  const res = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  if (res.status !== 200) {
    return null;
  }
  const body = (await res.json()) as { id?: string };
  return body.id?.toLowerCase() ?? null;
}
