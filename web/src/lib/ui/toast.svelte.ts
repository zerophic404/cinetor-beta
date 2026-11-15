// Transient toasts (download started, saved a copy, …) with one optional action.
// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
export interface ToastAction {
  label: string;
  run: () => void;
}

export interface ToastMsg {
  id: number;
  text: string;
  action?: ToastAction;
}

class Toasts {
  list = $state<ToastMsg[]>([]);
  private n = 0;

  show(text: string, action?: ToastAction, ms = 6000): number {
    const id = ++this.n;
    this.list = [...this.list, { id, text, action }];
    if (ms > 0) setTimeout(() => this.dismiss(id), ms);
    return id;
  }

  dismiss(id: number): void {
    this.list = this.list.filter((t) => t.id !== id);
  }
}

export const toasts = new Toasts();
