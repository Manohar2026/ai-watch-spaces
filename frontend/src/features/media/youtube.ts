const YOUTUBE_HOSTS = new Set([
  'youtube.com',
  'www.youtube.com',
  'm.youtube.com',
  'music.youtube.com',
  'youtu.be',
  'www.youtu.be',
  'youtube-nocookie.com',
  'www.youtube-nocookie.com',
]);

export interface YouTubePlayer {
  playVideo(): void;
  pauseVideo(): void;
  seekTo(seconds: number, allowSeekAhead: boolean): void;
  getCurrentTime(): number;
  getPlayerState(): number;
  destroy(): void;
}

export interface YouTubePlayerEvent {
  target: YouTubePlayer;
  data: number;
}

export interface YouTubePlayerErrorEvent {
  data: number;
}

interface YouTubePlayerOptions {
  videoId: string;
  playerVars?: Record<string, string | number>;
  events: {
    onReady: (event: YouTubePlayerEvent) => void;
    onStateChange?: (event: YouTubePlayerEvent) => void;
    onError?: (event: YouTubePlayerErrorEvent) => void;
    onAutoplayBlocked?: () => void;
  };
}

export interface YouTubeIframeApi {
  PlayerState: {
    UNSTARTED: number;
    ENDED: number;
    PLAYING: number;
    PAUSED: number;
    BUFFERING: number;
    CUED: number;
  };
  Player: new (element: HTMLElement, options: YouTubePlayerOptions) => YouTubePlayer;
}

declare global {
  interface Window {
    YT?: YouTubeIframeApi;
    onYouTubeIframeAPIReady?: () => void;
  }
}

let apiPromise: Promise<YouTubeIframeApi> | null = null;

/** Extracts an 11-character video ID from supported YouTube watch, share, embed, Shorts, or live URLs. */
export function getYouTubeVideoId(value: string): string | null {
  try {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || !YOUTUBE_HOSTS.has(url.hostname.toLowerCase())) return null;

    let videoId: string | null = null;
    if (url.hostname.toLowerCase().endsWith('youtu.be')) {
      videoId = url.pathname.split('/').filter(Boolean)[0] ?? null;
    } else if (url.pathname === '/watch') {
      videoId = url.searchParams.get('v');
    } else {
      const match = url.pathname.match(/^\/(?:embed|shorts)\/([^/?#]+)/);
      videoId = match?.[1] ?? null;
    }

    return videoId && /^[A-Za-z0-9_-]{11}$/.test(videoId) ? videoId : null;
  } catch {
    return null;
  }
}

export function isYouTubeHostUrl(value: string): boolean {
  try {
    return YOUTUBE_HOSTS.has(new URL(value).hostname.toLowerCase());
  } catch {
    return false;
  }
}

export function getYouTubeEmbedUrl(videoId: string): string {
  return `https://www.youtube.com/embed/${encodeURIComponent(videoId)}?playsinline=1&rel=0`;
}

/** Loads the official YouTube IFrame API once per browser session. */
export function loadYouTubeIframeApi(): Promise<YouTubeIframeApi> {
  if (window.YT?.Player) return Promise.resolve(window.YT);
  if (apiPromise) return apiPromise;

  apiPromise = new Promise<YouTubeIframeApi>((resolve, reject) => {
    const previousReady = window.onYouTubeIframeAPIReady;
    let settled = false;
    const finish = (api: YouTubeIframeApi) => {
      if (settled) return;
      settled = true;
      resolve(api);
    };

    window.onYouTubeIframeAPIReady = () => {
      previousReady?.();
      if (window.YT?.Player) finish(window.YT);
      else reject(new Error('The YouTube player API did not initialize.'));
    };

    const scriptUrl = 'https://www.youtube.com/iframe_api';
    let script = document.querySelector<HTMLScriptElement>(`script[src="${scriptUrl}"]`);
    if (!script) {
      script = document.createElement('script');
      script.src = scriptUrl;
      script.async = true;
      document.head.appendChild(script);
    }
    script.addEventListener('error', () => {
      apiPromise = null;
      reject(new Error('The YouTube player API could not be loaded.'));
    }, { once: true });

    window.setTimeout(() => {
      if (!settled && window.YT?.Player) finish(window.YT);
      else if (!settled) {
        apiPromise = null;
        reject(new Error('Timed out while loading the YouTube player API.'));
      }
    }, 15_000);
  });

  return apiPromise;
}

export function getYouTubePlayerErrorMessage(code: number): string {
  if (code === 100) return 'This YouTube video is private, removed, or unavailable.';
  if (code === 101 || code === 150) return 'The video owner has disabled playback on other websites. Choose a video that allows embedding.';
  if (code === 153) return 'YouTube could not verify the player origin. Reload the page and try again.';
  if (code === 2) return 'The YouTube video ID is invalid.';
  if (code === 5) return 'The YouTube player could not decode this video.';
  return `This YouTube video could not be played (error ${code}). Try another embeddable video.`;
}
