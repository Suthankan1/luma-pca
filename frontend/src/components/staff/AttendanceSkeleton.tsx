export function ClassesColumnSkeleton() {
  return <div className="classes-column" role="status" aria-label="Loading classes">
    <div className="panel classes-attendance-chart" aria-hidden="true"><Chart /></div>
    <section className="panel" aria-hidden="true"><Rows /></section>
  </div>;
}

export function StudentMonthSkeleton() {
  return <div role="status" aria-label="Loading student attendance">
    <div className="panel" aria-hidden="true"><div className="skeleton skeleton-title" /></div>
    <div className="panel panel-gap classes-attendance-chart" aria-hidden="true"><Chart /></div>
    <div className="panel panel-gap" aria-hidden="true"><Rows /></div>
  </div>;
}

function Chart() {
  return <>
    <div className="skeleton skeleton-title" />
    <div className="skeleton skeleton-card" />
  </>;
}

function Rows() {
  return <>
    <div className="skeleton skeleton-title" />
    {[0, 1, 2, 3].map(index => <div key={index} className="skeleton skeleton-line" />)}
  </>;
}
