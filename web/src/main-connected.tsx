import React from "react";
import ReactDOM from "react-dom/client";
import ConnectedApp from "./connected/ConnectedApp";
import "./styles.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode><ConnectedApp /></React.StrictMode>
);
