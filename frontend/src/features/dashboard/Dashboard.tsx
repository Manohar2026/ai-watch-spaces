import { Catalog } from '../catalog/Catalog';
import { useAuth } from '../auth/AuthProvider';

export function Dashboard() {
  const { user, logout, isSubmitting } = useAuth();

  if (!user) {
    return null;
  }

  return (
    <main className="dashboard-shell">
      <header className="dashboard-topbar">
        <a className="brand" href="/" aria-label="AI Watch Spaces home">
          <span className="brand-mark" aria-hidden="true">W</span>
          <span>watch<span className="brand-accent">spaces</span></span>
        </a>
        <div className="dashboard-user">
          <span className="dashboard-user-name">{user.displayName}</span>
          <span className="dashboard-user-role">{user.systemRole}</span>
          <button className="dashboard-logout" type="button" onClick={() => void logout()} disabled={isSubmitting}>
            Sign out
          </button>
        </div>
      </header>

      <section className="dashboard-content dashboard-content-wide">
        <p className="eyebrow"><span className="status-dot" /> YOUR WATCHSPACES HOME</p>
        <h1>Stories are better<br /><span>together.</span></h1>
        <p className="dashboard-lead">
          Browse published stories, explore their timelines, and discover the details behind each moment.
          Your catalogue is connected directly to the Spring Boot API.
        </p>

        <Catalog />

        <details className="account-details-panel">
          <summary>Account and profile details</summary>
          <dl className="account-details">
            <div><dt>Email</dt><dd>{user.email}</dd></div>
            <div><dt>Subtitle locale</dt><dd>{user.subtitleLocale}</dd></div>
            <div><dt>User ID</dt><dd className="user-id">{user.id}</dd></div>
          </dl>
        </details>
      </section>
    </main>
  );
}
