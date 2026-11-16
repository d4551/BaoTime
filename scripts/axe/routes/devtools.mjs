import { format } from 'node:url';

export const faceTimeOrigin = 'https://facetime.apple.com';
const loopbackHost = '127.0.0.1';

function isRoomPage(page) {
  if (page.type !== 'page') return false;
  const url = new URL(page.url);
  return url.origin === faceTimeOrigin && !url.username && !url.password && url.href !== `${faceTimeOrigin}/`;
}

export async function findRoom(port, timeoutMs) {
  const address = { hostname: loopbackHost, port };
  const discoveryUrl = format({ ...address, protocol: 'http', pathname: '/json/list' });
  const response = await fetch(discoveryUrl, {
    redirect: 'error',
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!response.ok) throw new Error(`DevTools discovery failed: ${response.status}`);
  const pages = await response.json();
  const rooms = pages.filter(isRoomPage);
  if (rooms.length !== 1) throw new Error(`Expected one FaceTime room page, found ${rooms.length}`);
  const [page] = rooms;
  const debuggerUrl = format({
    ...address,
    protocol: 'ws',
    pathname: `/devtools/page/${encodeURIComponent(page.id)}`,
    slashes: true,
  });
  if (page.webSocketDebuggerUrl !== debuggerUrl) throw new Error('Unexpected FaceTime DevTools endpoint');
  return page;
}
