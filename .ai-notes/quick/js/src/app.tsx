// What a server module would ship: plain Preact + hooks against host components.
import { useState, useEffect } from "preact/hooks";
import { call, registerSurface } from "./host";

// Host components are just strings: Preact treats them as "DOM" element types.
const Column = "Column" as any;
const Row = "Row" as any;
const Text = "Text" as any;
const Button = "Button" as any;

function Counter() {
  const [count, setCount] = useState(0);
  return (
    <Column padding={24} gap={12}>
      <Text variant="title">Count: {count}</Text>
      <Button onPress={() => setCount((v) => v + 1)}>Increase</Button>
    </Column>
  );
}

type Order = { id: number; title: string; total: number };

function Orders() {
  const [orders, setOrders] = useState<Order[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    call<Order[]>("orders", "list", { place: 1 }).then(setOrders, (e) => setError(e.code));
  }, []);

  useEffect(() => {
    const t = setTimeout(() => setTick((v) => v + 1), 50);
    return () => clearTimeout(t);
  }, [tick < 3 ? tick : -1]);

  if (error) return <Text tone="danger">Error: {error}</Text>;
  if (!orders) return <Text>Loading…</Text>;
  return (
    <Column gap={8}>
      <Text variant="caption">ticks {tick}</Text>
      <Button onPress={() => setOrders([...orders].reverse())}>Reverse</Button>
      <Button onPress={() => setOrders(orders.filter((o) => o.id !== orders[0].id))}>Drop first</Button>
      <Button onPress={() => { while (true) {} }}>Hang</Button>
      {orders.map((o) => (
        <Row key={o.id} testTag={"order-" + o.id}>
          <Text>{o.title}</Text>
          <Text variant="money">{o.total}</Text>
        </Row>
      ))}
    </Column>
  );
}

registerSurface("counter", Counter);
registerSurface("orders", Orders);
