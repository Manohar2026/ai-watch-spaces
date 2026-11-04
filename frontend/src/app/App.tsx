import { useEffect, useState } from 'react';
import { AuthProvider, useAuth } from '../features/auth/AuthProvider';
import { AuthScreen } from '../features/auth/AuthScreen';
import { Dashboard } from '../features/dashboard/Dashboard';
import { CatalogPage } from '../features/catalog/CatalogPage';

type View = 'home' | 'login' | 'register' | 'catalog';

function AppContent() {
  const [view, setView] = useState<View>('home');
  const [authNotice, setAuthNotice] = useState<string | undefined>();
  const { user } = useAuth();

  useEffect(() => {
    if (!user && view === 'catalog') {
      setView('home');
    }
  }, [user, view]);

  if (user) {
    if (view === 'catalog') {
      return <CatalogPage onBack={() => setView('home')} />;
    }
    return <Dashboard onBrowseCatalog={() => setView('catalog')} />;
  }

  if (view !== 'home') {
    return (
      <AuthScreen
        mode={view}
        notice={authNotice}
        onModeChange={(mode, notice) => {
          setAuthNotice(notice);
          setView(mode);
        }}
        onBack={() => {
          setAuthNotice(undefined);
          setView('home');
        }}
      />
    );
  }

  return (
    <main className="page-shell">
      <nav className="topbar" aria-label="Main navigation">
        <a className="brand" href="/" aria-label="AI Watch Spaces home">
          <span className="brand-mark" aria-hidden="true">W</span>
          <span>watch<span className="brand-accent">spaces</span></span>
        </a>
        <span className="topbar-note">A shared way to experience stories</span>
        <div className="topbar-actions">
          <button className="text-button" type="button" onClick={() => setView('login')}>Sign in</button>
          <button className="topbar-cta" type="button" onClick={() => setView('register')}>Get started <span aria-hidden="true">↗</span></button>
        </div>
      </nav>

      <section className="hero" aria-labelledby="hero-title">
        <div className="hero-copy">
          <p className="eyebrow"><span className="status-dot" /> YOUR PEOPLE. YOUR STORIES. IN SYNC.</p>
          <h1 id="hero-title">Some stories are<br />better <span>together.</span></h1>
          <p className="hero-description">
            Bring your people into one watch space. Stay in sync, share reactions,
            and discover more about every moment.
          </p>
          <div className="hero-actions">
            <button className="primary-button" type="button" onClick={() => setView('register')}>
              Create your account <span aria-hidden="true">→</span>
            </button>
            <span className="hero-caption">A new kind of shared screen time.</span>
          </div>
        </div>

        <div className="visual-stage" aria-label="Illustration of friends watching together">
          <div className="orbit orbit-one" />
          <div className="orbit orbit-two" />
          <div className="screen-card">
            <div className="screen-topline"><span className="live-indicator" /> LIVE WATCH SPACE <span>•••</span></div>
            <div className="screen-art">
              <div className="art-sun" />
              <div className="art-mountain mountain-back" />
              <div className="art-mountain mountain-front" />
              <div className="art-glow" />
              <span className="art-label">THE MOMENT IS YOURS</span>
            </div>
            <div className="screen-controls">
              <span className="play-icon">Ⅱ</span>
              <span className="progress-track"><span /></span>
              <span className="time-label">24:18</span>
            </div>
          </div>
          <div className="participant participant-one"><span>AM</span><small>Alex</small></div>
          <div className="participant participant-two"><span>SK</span><small>Sam</small></div>
          <div className="participant participant-three"><span>JR</span><small>Jordan</small></div>
          <div className="floating-note"><span className="note-icon">✦</span><span><strong>That scene!</strong><small>Everyone is watching</small></span></div>
          <div className="stage-index">01 <span>/</span> 04</div>
        </div>
      </section>

      <section className="feature-section" id="how-it-works" aria-labelledby="feature-title">
        <div className="section-heading">
          <p className="eyebrow">MORE THAN PRESSING PLAY</p>
          <h2 id="feature-title">A space for every kind of fan.</h2>
        </div>
        <div className="feature-grid">
          <article className="feature-card">
            <span className="feature-number">01</span>
            <h3>Watch in sync</h3>
            <p>Create a space, invite friends, and experience playback together.</p>
          </article>
          <article className="feature-card">
            <span className="feature-number">02</span>
            <h3>Talk about the moment</h3>
            <p>Keep conversations, reactions, and room presence alongside the story.</p>
          </article>
          <article className="feature-card">
            <span className="feature-number">03</span>
            <h3>Go beyond the screen</h3>
            <p>Explore contextual facts, interactive trivia, and AI-assisted answers.</p>
          </article>
        </div>
      </section>

      <footer className="footer">
        <span>watchspaces<span className="brand-accent">.</span></span>
        <span>Made for the moments you share.</span>
      </footer>
    </main>
  );
}

function App() {
  return (
    <AuthProvider>
      <AppContent />
    </AuthProvider>
  );
}

export default App;
