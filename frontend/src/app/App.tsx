const features = [
  {
    number: '01',
    title: 'Watch in sync',
    description: 'Create a space, invite friends, and experience playback together.',
  },
  {
    number: '02',
    title: 'Talk about the moment',
    description: 'Keep conversations, reactions, and room presence alongside the story.',
  },
  {
    number: '03',
    title: 'Go beyond the screen',
    description: 'Explore contextual facts, interactive trivia, and AI-assisted answers.',
  },
];

function App() {
  return (
    <main className="page-shell">
      <nav className="topbar" aria-label="Main navigation">
        <a className="brand" href="/" aria-label="AI Watch Spaces home">
          <span className="brand-mark" aria-hidden="true">W</span>
          <span>watch<span className="brand-accent">spaces</span></span>
        </a>
        <span className="topbar-note">A shared way to experience stories</span>
        <a className="topbar-link" href="#how-it-works">Explore <span aria-hidden="true">↗</span></a>
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
            <a className="primary-button" href="#how-it-works">Discover the experience <span aria-hidden="true">→</span></a>
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
          {features.map((feature) => (
            <article className="feature-card" key={feature.number}>
              <span className="feature-number">{feature.number}</span>
              <h3>{feature.title}</h3>
              <p>{feature.description}</p>
            </article>
          ))}
        </div>
      </section>

      <footer className="footer">
        <span>watchspaces<span className="brand-accent">.</span></span>
        <span>Made for the moments you share.</span>
      </footer>
    </main>
  );
}

export default App;
